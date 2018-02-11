package millfork.compiler

import java.util.concurrent.atomic.AtomicLong

import millfork.{CompilationFlag, CompilationOptions}
import millfork.assembly._
import millfork.env._
import millfork.node.{Register, _}
import millfork.assembly.AddrMode._
import millfork.assembly.Opcode._
import millfork.error.ErrorReporting

import scala.collection.JavaConverters._

/**
  * @author Karol Stasiak
  */
//noinspection NotImplementedCode,ScalaUnusedSymbol
object MfCompiler {


  private val labelCounter = new AtomicLong

  def nextLabel(prefix: String): String = "." + prefix + "__" + labelCounter.incrementAndGet().formatted("%05d")

  def compile(ctx: CompilationContext): List[AssemblyLine] = {
    ctx.env.nameCheck(ctx.function.code)
    val chunk = StatementCompiler.compile(ctx, ctx.function.code)
    val prefix = (if (ctx.function.interrupt) {
      if (ctx.options.flag(CompilationFlag.EmitCmosOpcodes)) {
        List(
          AssemblyLine.implied(PHA),
          AssemblyLine.implied(PHX),
          AssemblyLine.implied(PHY),
          AssemblyLine.implied(CLD))
      } else {
        List(
          AssemblyLine.implied(PHA),
          AssemblyLine.implied(TXA),
          AssemblyLine.implied(PHA),
          AssemblyLine.implied(TYA),
          AssemblyLine.implied(PHA),
          AssemblyLine.implied(CLD))
      }
    } else Nil) ++ stackPointerFixAtBeginning(ctx)
    val label = AssemblyLine.label(Label(ctx.function.name)).copy(elidable = false)
    label :: (prefix ++ chunk)
  }

  def stackPointerFixAtBeginning(ctx: CompilationContext): List[AssemblyLine] = {
    val m = ctx.function
    if (m.stackVariablesSize == 0) return Nil
    if (ctx.options.flag(CompilationFlag.EmitIllegals)) {
      if (m.stackVariablesSize > 4)
        return List(
          AssemblyLine.implied(TSX),
          AssemblyLine.immediate(LDA, 0xff),
          AssemblyLine.immediate(SBX, m.stackVariablesSize),
          AssemblyLine.implied(TXS))
    }
    List.fill(m.stackVariablesSize)(AssemblyLine.implied(PHA))
  }

}
