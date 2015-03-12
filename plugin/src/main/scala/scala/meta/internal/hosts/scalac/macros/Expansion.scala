package scala.meta
package internal.hosts.scalac
package macros

import org.scalameta.reflection._
import org.scalameta.invariants._
import scala.reflect.internal.Flags._
import scala.reflect.internal.Mode
import scala.reflect.internal.util.Collections._
import scala.reflect.internal.util.Statistics
import scala.tools.nsc.typechecker.MacrosStats._
import scala.reflect.runtime.ReflectionUtils
import scala.reflect.macros.runtime.AbortMacroException
import scala.util.control.ControlThrowable
import scala.collection.mutable
import scala.meta.dialects.Scala211
import scala.meta.internal.{ast => m}
import scala.meta.internal.eval.evalFunc
import scala.meta.macros.{Context => ScalametaMacroContext}
import scala.meta.internal.hosts.scalac.contexts.{MacroContext => ScalahostMacroContext}
import scala.meta.internal.hosts.scalac.{PluginBase => ScalahostPlugin, Scalahost}

trait Expansion extends scala.reflect.internal.show.Printers {
  self: ScalahostPlugin =>

  import global._
  import definitions._
  import treeInfo._
  import analyzer.{MacroPlugin => NscMacroPlugin, MacroContext => ScalareflectMacroContext, _}

  def scalahostMacroExpand(typer: Typer, expandee: Tree, mode: Mode, pt: Type): Option[Tree] = {
    val XtensionQuasiquoteTerm = "shadow scala.meta quasiquotes"
    val macroSignatures = expandee.symbol.annotations.filter(_.atp.typeSymbol == MacroImplAnnotation)
    val expanded = macroSignatures match {
      case _ :: AnnotationInfo(_, List(ScalahostSignature(implDdef)), _) :: Nil =>
        object scalahostMacroExpander extends DefMacroExpander(typer, expandee, mode, pt) {
          private def isDelayed(tree: Tree): Boolean = {
            val macros = Class.forName("scala.tools.nsc.typechecker.Macros$class", false, getClass.getClassLoader)
            val isDelayedMethod = macros.getDeclaredMethods().filter(_.getName == "isDelayed").head
            isDelayedMethod.setAccessible(true)
            isDelayedMethod.invoke(null, analyzer, tree).require[Boolean]
          }
          private def appendDelayed(tree: Tree, undets: mutable.Set[Int]): Unit = {
            val macros = Class.forName("scala.tools.nsc.typechecker.Macros", false, getClass.getClassLoader)
            val delayedGetter = macros.getDeclaredMethods().filter(_.getName == "scala$tools$nsc$typechecker$Macros$$delayed").head
            delayedGetter.setAccessible(true)
            val delayed = delayedGetter.invoke(this).require[mutable.WeakHashMap[Tree, scala.collection.mutable.Set[Int]]]
            delayed += tree -> undets
          }
          private def calculateUndetparams(expandee: Tree): mutable.Set[Int] = {
            val macros = Class.forName("scala.tools.nsc.typechecker.Macros$class", false, getClass.getClassLoader)
            val calculatedUndetparamsMethod = macros.getDeclaredMethods().filter(_.getName == "scala$tools$nsc$typechecker$Macros$$calculateUndetparams").head
            calculatedUndetparamsMethod.setAccessible(true)
            calculatedUndetparamsMethod.invoke(null, analyzer, expandee).require[mutable.Set[Int]]
          }
          private def macroArgs(): MacroArgs = {
            val treeInfo.Applied(core, _, _) = expandee
            val prefix = core match { case Select(qual, _) => qual; case _ => EmptyTree }
            val context = expandee.attachments.get[MacroRuntimeAttachment].flatMap(_.macroContext).getOrElse(macroContext(typer, prefix, expandee))
            MacroArgs(context, Nil)
          }
          // NOTE: magic name. essential for detailed and sane stack traces for exceptions in macro expansion logic
          private def macroExpandWithRuntime(rc: ScalareflectMacroContext): Any = {
            def fail(errorMessage: String) = throw new AbortMacroException(rc.macroApplication.pos, errorMessage)
            implicit val mc = Scalahost.mkMacroContext[global.type](rc)
            val mresult: Any = {
              if (sys.props("macro.debug") != null) println(rc.macroApplication)
              val q"$_[..$gtargs](...$gargss)" = rc.macroApplication
              val mtargs = gtargs.map(gtarg => mc.toMtype(gtarg.tpe))
              val margss = mmap(gargss)(mc.toMtree(_, classOf[m.Term]))
              val mmacroargs = mtargs ++ margss.flatten :+ mc
              if (currentRun.compiles(rc.macroApplication.symbol)) {
                val macroUnit = currentRun.units.find{ u =>
                   u.body.exists(x => x.symbol == rc.macroApplication.symbol)}.get.body
                val reflectMacroMethod = macroUnit.find({ case mthd: DefDef =>
                    mthd.symbol.owner == rc.macroApplication.symbol.owner &&
                    mthd.name.toString == rc.macroApplication.symbol.name.encoded  + "$impl"
                  case _ => false
                }).get
                val mMacroBody = mc.toMtree(reflectMacroMethod, classOf[m.Defn.Def])
                val args = List(mtargs,  margss.flatten, List(mc)).filterNot(_.isEmpty)
                evalFunc(mMacroBody, args:_*)
              } else {
                val jclassloader = {
                  val findMacroClassLoader = analyzer.getClass.getMethods().filter(_.getName.endsWith("findMacroClassLoader")).head
                  findMacroClassLoader.setAccessible(true)
                  findMacroClassLoader.invoke(analyzer).asInstanceOf[ClassLoader]
                }
                val jclass = Class.forName(rc.macroApplication.symbol.owner.fullName + "$", true, jclassloader)
                val jmeths = jclass.getDeclaredMethods().filter(_.getName == rc.macroApplication.symbol.name.encoded + "$impl").toList
                val jmeth = jmeths match {
                  case List(single) => single
                  case Nil => fail("no precompiled implementation found for scala.meta macro")
                  case other => fail("cannot pick from multiple precompiled implementations for scala.meta macro")
                }
                val jinstance = ReflectionUtils.staticSingletonInstance(jclass)
                jmeth.setAccessible(true)
                if (sys.props("macro.debug") != null) println(s"invoking $jmeth(${mmacroargs.mkString(", ")})")
                val result = jmeth.invoke(jinstance, mmacroargs: _*)
                if (sys.props("macro.debug") != null) println(result)
                result
              }
            }
            val gresult: Any = mresult match {
              case mtree: m.Tree =>
                if (sys.props("macro.debug") != null) println(mtree.show[Raw])
                val gtree: g.Tree = mc.toGtree(mtree)
                if (sys.props("macro.debug") != null) println(g.showRaw(gtree))
                attachExpansionString(expandee, gtree, mtree.show[Code])
                gtree
              case other =>
                fail("scala.meta macros must return a scala.meta.Term; returned value is " + (if (other == null) "null" else "of " + other.getClass))
            }
            gresult
          }
          override protected def expand(desugared: Tree): Tree = {
            def showDetailed(tree: Tree) = showRaw(tree, printIds = true, printTypes = true)
            def summary() = s"expander = $this, expandee = ${showDetailed(expandee)}, desugared = ${if (expandee == desugared) () else showDetailed(desugared)}"
            if (macroDebugVerbose) println(s"macroExpand: ${summary()}")
            linkExpandeeAndDesugared(expandee, desugared)
            val start = if (Statistics.canEnable) Statistics.startTimer(macroExpandNanos) else null
            if (Statistics.canEnable) Statistics.incCounter(macroExpandCount)
            try {
              withInfoLevel(nodePrinters.InfoLevel.Quiet) { // verbose printing might cause recursive macro expansions
                if (expandee.symbol.isErroneous || (expandee exists (_.isErroneous))) {
                  val reason = if (expandee.symbol.isErroneous) "not found or incompatible macro implementation" else "erroneous arguments"
                  macroLogVerbose(s"cancelled macro expansion because of $reason: $expandee")
                  onFailure(typer.infer.setError(expandee))
                } else try {
                  val expanded = {
                    val wasDelayed  = isDelayed(expandee)
                    val undetparams = calculateUndetparams(expandee)
                    val nowDelayed  = !typer.context.macrosEnabled || undetparams.nonEmpty
                    (wasDelayed, nowDelayed) match {
                      case (true, true) =>
                        Delay(expandee)
                      case (true, false) =>
                        val expanded = macroExpandAll(typer, expandee)
                        if (expanded exists (_.isErroneous)) Failure(expandee)
                        else Skip(expanded)
                      case (false, true) =>
                        macroLogLite("macro expansion is delayed: %s".format(expandee))
                        appendDelayed(expandee, undetparams)
                        expandee updateAttachment MacroRuntimeAttachment(delayed = true, typerContext = typer.context, macroContext = Some(macroArgs().c))
                        Delay(expandee)
                      case (false, false) =>
                        import typer.TyperErrorGen._
                        macroLogLite("performing macro expansion %s at %s".format(expandee, expandee.pos))
                        val args = macroArgs()
                        try {
                          val numErrors    = reporter.ERROR.count
                          def hasNewErrors = reporter.ERROR.count > numErrors
                          val expanded = { pushMacroContext(args.c); macroExpandWithRuntime(args.c) }
                          if (hasNewErrors) MacroGeneratedTypeError(expandee)
                          def validateResultingTree(expanded: Tree) = {
                            macroLogVerbose("original:")
                            macroLogLite("" + expanded + "\n" + showRaw(expanded))
                            val freeSyms = expanded.freeTerms ++ expanded.freeTypes
                            freeSyms foreach (sym => MacroFreeSymbolError(expandee, sym))
                            // Macros might have spliced arguments with range positions into non-compliant
                            // locations, notably, under a tree without a range position. Or, they might
                            // splice a tree that `resetAttrs` has assigned NoPosition.
                            //
                            // Here, we just convert all positions in the tree to offset positions, and
                            // convert NoPositions to something sensible.
                            //
                            // Given that the IDE now sees the expandee (by using -Ymacro-expand:discard),
                            // this loss of position fidelity shouldn't cause any real problems.
                            //
                            // Alternatively, we could pursue a way to exclude macro expansions from position
                            // invariant checking, or find a way not to touch expansions that happen to validate.
                            //
                            // This would be useful for cases like:
                            //
                            //    macro1 { macro2 { "foo" } }
                            //
                            // to allow `macro1` to see the range position of the "foo".
                            val expandedPos = enclosingMacroPosition.focus
                            def fixPosition(pos: Position) =
                              if (pos == NoPosition) expandedPos else pos.focus
                            expanded.foreach(t => t.pos = fixPosition(t.pos))

                            val result = atPos(enclosingMacroPosition.focus)(expanded)
                            Success(result)
                          }
                          expanded match {
                            case expanded: Expr[_] if expandee.symbol.isTermMacro => validateResultingTree(expanded.tree)
                            case expanded: Tree if expandee.symbol.isTermMacro => validateResultingTree(expanded)
                            case _ => MacroExpansionHasInvalidTypeError(expandee, expanded)
                          }
                        } catch {
                          case ex: Throwable =>
                            popMacroContext()
                            val realex = ReflectionUtils.unwrapThrowable(ex)
                            realex match {
                              case ex: AbortMacroException => MacroGeneratedAbort(expandee, ex)
                              case ex: ControlThrowable => throw ex
                              case ex: TypeError => MacroGeneratedTypeError(expandee, ex)
                              case _ => MacroGeneratedException(expandee, realex)
                            }
                        } finally {
                          expandee.removeAttachment[MacroRuntimeAttachment]
                        }
                      }
                  }
                  expanded match {
                    case Success(expanded) =>
                      // also see http://groups.google.com/group/scala-internals/browse_thread/thread/492560d941b315cc
                      val expanded1 = try onSuccess(duplicateAndKeepPositions(expanded)) finally popMacroContext()
                      if (!hasMacroExpansionAttachment(expanded1)) linkExpandeeAndExpanded(expandee, expanded1)
                      if (settings.Ymacroexpand.value == settings.MacroExpand.Discard) expandee.setType(expanded1.tpe)
                      else expanded1
                    case Fallback(fallback) => onFallback(fallback)
                    case Delayed(delayed) => onDelayed(delayed)
                    case Skipped(skipped) => onSkipped(skipped)
                    case Failure(failure) => onFailure(failure)
                  }
                } catch {
                  case typer.TyperErrorGen.MacroExpansionException => onFailure(expandee)
                }
              }
            } finally {
              if (Statistics.canEnable) Statistics.stopTimer(macroExpandNanos, start)
            }
          }
        }
        scalahostMacroExpander(expandee)
      case _ =>
        new DefMacroExpander(typer, expandee, mode, pt).apply(expandee)
    }
    val hasMeaningfulExpansion = hasMacroExpansionAttachment(expanded) && settings.Ymacroexpand.value != settings.MacroExpand.Discard
    if (hasMeaningfulExpansion) attachExpansionString(expandee, expanded, showCode(expanded))
    Some(expanded)
  }

  private class DefMacroExpander(typer: Typer, expandee: Tree, mode: Mode, outerPt: Type)
  extends analyzer.DefMacroExpander(typer, expandee, mode, outerPt) {
    override def onSuccess(expanded0: Tree) = {
      linkExpandeeAndExpanded(expandee, expanded0)
      val result = super.onSuccess(expanded0)
      attachExpansionString(expandee, expanded0, showCode(expanded0))
      expanded0.removeAttachment[MacroExpansionAttachment] // NOTE: removes MEA from the initial expansion wrapped in a blackbox ascription
      result
    }
  }

  private def linkExpandeeAndExpanded(expandee: Tree, expanded: Tree): Unit = {
    analyzer.linkExpandeeAndExpanded(expandee, expanded)
    syncPropertyBags(List(expandee, expanded), Map("expandeeTree" -> expandee, "expandedTree" -> expanded))
  }

  private def attachExpansionString(expandee: Tree, expanded: Tree, expansionString: String): Unit = {
    syncPropertyBags(List(expandee, expanded), Map("expansionString" -> expansionString))
  }

  private def syncPropertyBags(trees: List[Tree], extra: Map[String, Any]): Unit = {
    val merged = trees.map(_.metadata.toMap).reduce((b1, b2) => b1 ++ b2) ++ extra
    trees.foreach(_.metadata ++= merged)
  }
}
