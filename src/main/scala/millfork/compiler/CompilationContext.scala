package millfork.compiler

import millfork.{CompilationFlag, CompilationOptions}
import millfork.env.{Environment, Label, NormalFunction}

/**
  * @author Karol Stasiak
  */
case class CompilationContext(env: Environment,
                              function: NormalFunction,
                              extraStackOffset: Int,
                              options: CompilationOptions,
                              breakLabels: Map[String, Label] = Map(),
                              continueLabels: Map[String, Label] = Map()){
  def withInlinedEnv(environment: Environment): CompilationContext = {
    val newEnv = new Environment(Some(env), MfCompiler.nextLabel("en"))
    newEnv.things ++= environment.things
    copy(env = newEnv)
  }

  def addLabels(names: Set[String], breakLabel: Label, continueLabel: Label): CompilationContext = {
    var b = breakLabels
    var c = continueLabels
    names.foreach{name =>
      b += (name -> breakLabel)
      c += (name -> continueLabel)
    }
    this.copy(breakLabels = b, continueLabels = c)
  }

  def addStack(i: Int): CompilationContext = this.copy(extraStackOffset = extraStackOffset + i)

  def neverCheckArrayBounds: CompilationContext =
    this.copy(options = options.copy(commandLineFlags = options.commandLineFlags + (CompilationFlag.CheckIndexOutOfBounds -> false)))
}
