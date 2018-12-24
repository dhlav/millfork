package millfork

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.util.Locale

import millfork.assembly.mos.AssemblyLine
import millfork.assembly.mos.opt._
import millfork.assembly.z80.opt.Z80OptimizationPresets
import millfork.buildinfo.BuildInfo
import millfork.cli.{CliParser, CliStatus}
import millfork.compiler.LabelGenerator
import millfork.compiler.mos.MosCompiler
import millfork.env.Environment
import millfork.error.{ConsoleLogger, Logger}
import millfork.node.StandardCallGraph
import millfork.output._
import millfork.parser.{MosSourceLoadingQueue, ZSourceLoadingQueue}

/**
  * @author Karol Stasiak
  */

case class Context(errorReporting: Logger,
                   inputFileNames: List[String],
                   outputFileName: Option[String] = None,
                   runFileName: Option[String] = None,
                   optimizationLevel: Option[Int] = None,
                   zpRegisterSize: Option[Int] = None,
                   platform: Option[String] = None,
                   outputAssembly: Boolean = false,
                   outputLabels: Boolean = false,
                   includePath: List[String] = Nil,
                   flags: Map[CompilationFlag.Value, Boolean] = Map(),
                   features: Map[String, Long] = Map(),
                   verbosity: Option[Int] = None) {
  def changeFlag(f: CompilationFlag.Value, b: Boolean): Context = {
    if (flags.contains(f)) {
      if (flags(f) != b) {
        errorReporting.error("Conflicting flags")
      }
      this
    } else {
      copy(flags = this.flags + (f -> b))
    }
  }
}

object Main {


  def main(args: Array[String]): Unit = {
    val errorReporting = new ConsoleLogger
    implicit val __implicitLogger: Logger = errorReporting

    if (args.isEmpty) {
      errorReporting.info("For help, use --help")
    }

    val startTime = System.nanoTime()
    val (status, c0) = parser(errorReporting).parse(Context(errorReporting, Nil), args.toList)
    status match {
      case CliStatus.Quit => return
      case CliStatus.Failed =>
        errorReporting.fatalQuit("Invalid command line")
      case CliStatus.Ok => ()
    }
    errorReporting.assertNoErrors("Invalid command line")
    errorReporting.verbosity = c0.verbosity.getOrElse(0)
    if (c0.inputFileNames.isEmpty) {
      errorReporting.fatalQuit("No input files")
    }

    errorReporting.debug("millfork version " + BuildInfo.version)
    errorReporting.trace(s"Copyright (C) $copyrightYears  Karol Stasiak")
    errorReporting.trace("This program comes with ABSOLUTELY NO WARRANTY.")
    errorReporting.trace("This is free software, and you are welcome to redistribute it under certain conditions")
    errorReporting.trace("You should have received a copy of the GNU General Public License along with this program. If not, see https://www.gnu.org/licenses/")
    val c = fixMissingIncludePath(c0)
    if (c.includePath.isEmpty) {
      errorReporting.warn("Failed to detect the default include directory, consider using the -I option")
    }

    val platform = Platform.lookupPlatformFile("." :: c.includePath, c.platform.getOrElse {
      errorReporting.info("No platform selected, defaulting to `c64`")
      "c64"
    })
    val options = CompilationOptions(platform, c.flags, c.outputFileName, c.zpRegisterSize.getOrElse(platform.zpRegisterSize), c.features, JobContext(errorReporting, new LabelGenerator))
    errorReporting.debug("Effective flags: ")
    options.flags.toSeq.sortBy(_._1).foreach{
      case (f, b) => errorReporting.debug(f"    $f%-30s : $b%s")
    }

    val output = c.outputFileName.getOrElse("a")
    val assOutput = output + ".asm"
    val labelOutput = output + ".lbl"
//    val prgOutputs = (platform.outputStyle match {
//      case OutputStyle.Single => List("default")
//      case OutputStyle.PerBank => platform.bankNumbers.keys.toList
//    }).map(bankName => bankName -> {
//      if (bankName == "default") {
//        if (output.endsWith(platform.fileExtension)) output else output + platform.fileExtension
//      } else {
//        s"${output.stripSuffix(platform.fileExtension)}.$bankName${platform.fileExtension}"
//      }
//    }).toMap

    val result: AssemblerOutput = CpuFamily.forType(platform.cpu) match {
      case CpuFamily.M6502 => assembleForMos(c, platform, options)
      case CpuFamily.I80 => assembleForI80(c, platform, options)
    }

    if (c.outputAssembly) {
      val path = Paths.get(assOutput)
      errorReporting.debug("Writing assembly to " + path.toAbsolutePath)
      Files.write(path, result.asm.mkString("\n").getBytes(StandardCharsets.UTF_8))
    }
    if (c.outputLabels) {
      val path = Paths.get(labelOutput)
      errorReporting.debug("Writing labels to " + path.toAbsolutePath)
      Files.write(path, result.labels.sortWith { (a, b) =>
        val aLocal = a._1.head == '.'
        val bLocal = b._1.head == '.'
        if (aLocal == bLocal) a._1 < b._1
        else b._1 < a._1
      }.groupBy(_._2).values.map(_.head).toSeq.sortBy(_._2).map { case (l, a) =>
        val normalized = l.replace('$', '_').replace('.', '_')
        s"al ${a.toHexString} .$normalized"
      }.mkString("\n").getBytes(StandardCharsets.UTF_8))
    }
    val defaultPrgOutput = if (output.endsWith(platform.fileExtension)) output else output + platform.fileExtension
    result.code.foreach{
      case (bankName, code) =>
        val prgOutput = if (bankName == "default") {
          defaultPrgOutput
        } else {
          s"${output.stripSuffix(platform.fileExtension)}.$bankName${platform.fileExtension}"
        }
        val path = Paths.get(prgOutput)
        errorReporting.debug("Writing output to " + path.toAbsolutePath)
        errorReporting.debug(s"Total output size: ${code.length} bytes")
        Files.write(path, code)
    }
    errorReporting.debug(s"Total time: ${Math.round((System.nanoTime() - startTime)/1e6)} ms")
    c.runFileName.foreach(program =>
      new ProcessBuilder(program, Paths.get(defaultPrgOutput).toAbsolutePath.toString).start()
    )
    if (platform.generateBbcMicroInfFile) {
      val start = platform.codeAllocators("default").startAt
      val codeLength = result.code("default").length
      Files.write(Paths.get(defaultPrgOutput+".inf"),
        s"$defaultPrgOutput ${start.toHexString} ${start.toHexString} ${codeLength.toHexString}".getBytes(StandardCharsets.UTF_8))
    }
  }

  private def getDefaultIncludePath: Either[String, String] = {
    try {
      var where = new File(getClass.getProtectionDomain.getCodeSource.getLocation.toURI).getParentFile
      if ((where.getName == "scala-2.12" || where.getName == "scala-2.13") && where.getParentFile.getName == "target") {
        where = where.getParentFile.getParentFile
      }
      val dir = new File(where.getAbsolutePath + File.separatorChar + "include")
      if (dir.exists()) {
        Right(dir.getAbsolutePath)
      } else {
        Left(s"The ${dir.getAbsolutePath} directory doesn't exist")
      }
    } catch {
      case  e: Exception => Left(e.getMessage)
    }
  }

  private def getAllDefaultPlatforms: Seq[String] = {
    (getDefaultIncludePath match {
      case Left(_) => Seq(
        "c64", "c64_scpu", "c64_scpu16", "c64_crt9k", "c64_crt16k", "lunix",
        "vic20", "vic20_3k", "vic20_8k", "vic20_a000",
        "c16", "plus4", "pet", "c128",
        "a8", "bbcmicro", "apple2",
        "nes_mmc4", "nes_small", "vcs",
        "zxspectrum", "zxspectrum_8080", "pc88", "cpc464",
        "cpm", "cpm_z80")
      case Right(path) =>
        Seq(new File(".").list(), new File(path).list())
          .filter(_ ne null)
          .flatMap(_.toSeq)
          .filter(_.endsWith(".ini"))
          .map(_.stripSuffix(".ini"))
    }).sorted
  }

  private def fixMissingIncludePath(c: Context)(implicit log: Logger): Context = {
    if (c.includePath.isEmpty) {
      getDefaultIncludePath match {
        case Left(err) =>
          log.debug(s"Failed to find the default include path: $err")
        case Right(path) =>
          log.debug(s"Automatically detected include path: $path")
          return c.copy(includePath = List(path))
      }
    }
    c
  }

  private def assembleForMos(c: Context, platform: Platform, options: CompilationOptions): AssemblerOutput = {
    val optLevel = c.optimizationLevel.getOrElse(0)
    val unoptimized = new MosSourceLoadingQueue(
      initialFilenames = c.inputFileNames,
      includePath = c.includePath,
      options = options).run()

    val program = if (optLevel > 0) {
      OptimizationPresets.NodeOpt.foldLeft(unoptimized)((p, opt) => p.applyNodeOptimization(opt, options))
    } else {
      unoptimized
    }
    val callGraph = new StandardCallGraph(program, options.log)

    val env = new Environment(None, "", platform.cpuFamily, options.jobContext)
    env.collectDeclarations(program, options)

    val assemblyOptimizations = optLevel match {
      case 0 => Nil
      case 1 => OptimizationPresets.QuickPreset
      case i if i >= 9 => List(SuperOptimizer)
      case _ =>
        val goodExtras = List(
          if (options.flag(CompilationFlag.EmitEmulation65816Opcodes)) SixteenOptimizations.AllForEmulation else Nil,
          if (options.flag(CompilationFlag.EmitNative65816Opcodes)) SixteenOptimizations.AllForNative else Nil,
          if (options.zpRegisterSize > 0) ZeropageRegisterOptimizations.All else Nil
        ).flatten
        val extras = List(
          if (options.flag(CompilationFlag.EmitIllegals)) UndocumentedOptimizations.All else Nil,
          if (options.flag(CompilationFlag.Emit65CE02Opcodes)) CE02Optimizations.All else Nil,
          if (options.flag(CompilationFlag.EmitCmosOpcodes)) CmosOptimizations.All else LaterOptimizations.Nmos,
          if (options.flag(CompilationFlag.EmitHudsonOpcodes)) HudsonOptimizations.All else Nil,
          if (options.flag(CompilationFlag.EmitEmulation65816Opcodes)) SixteenOptimizations.AllForEmulation else Nil,
          if (options.flag(CompilationFlag.EmitNative65816Opcodes)) SixteenOptimizations.AllForNative else Nil,
          if (options.flag(CompilationFlag.DangerousOptimizations)) DangerousOptimizations.All else Nil
        ).flatten
        val goodCycle = List.fill(optLevel - 2)(OptimizationPresets.Good ++ goodExtras).flatten
        val mainCycle = List.fill(optLevel - 1)(OptimizationPresets.AssOpt ++ extras).flatten
        goodCycle ++ mainCycle ++ goodCycle
    }

    // compile
    val assembler = new MosAssembler(program, env, platform)
    val result = assembler.assemble(callGraph, assemblyOptimizations, options)
    options.log.assertNoErrors("Codegen failed")
    options.log.debug(f"Unoptimized code size: ${assembler.unoptimizedCodeSize}%5d B")
    options.log.debug(f"Optimized code size:   ${assembler.optimizedCodeSize}%5d B")
    options.log.debug(f"Gain:                   ${(100L * (assembler.unoptimizedCodeSize - assembler.optimizedCodeSize) / assembler.unoptimizedCodeSize.toDouble).round}%5d%%")
    options.log.debug(f"Initialized variables: ${assembler.initializedVariablesSize}%5d B")
    result
  }

  private def assembleForI80(c: Context, platform: Platform, options: CompilationOptions): AssemblerOutput = {
    val optLevel = c.optimizationLevel.getOrElse(0)
    val unoptimized = new ZSourceLoadingQueue(
      initialFilenames = c.inputFileNames,
      includePath = c.includePath,
      options = options).run()

    val program = if (optLevel > 0) {
      OptimizationPresets.NodeOpt.foldLeft(unoptimized)((p, opt) => p.applyNodeOptimization(opt, options))
    } else {
      unoptimized
    }
    val callGraph = new StandardCallGraph(program, options.log)

    val env = new Environment(None, "", platform.cpuFamily, options.jobContext)
    env.collectDeclarations(program, options)

    val assemblyOptimizations = optLevel match {
      case 0 => Nil
      case _ => platform.cpu match {
        case Cpu.Z80 | Cpu.EZ80 => Z80OptimizationPresets.GoodForZ80
        case Cpu.Intel8080 => Z80OptimizationPresets.GoodForIntel8080
        case Cpu.Sharp => Z80OptimizationPresets.GoodForSharp
        case _ => Nil
      }
    }

    // compile
    val assembler = new Z80Assembler(program, env, platform)
    val result = assembler.assemble(callGraph, assemblyOptimizations, options)
    options.log.assertNoErrors("Codegen failed")
    options.log.debug(f"Unoptimized code size: ${assembler.unoptimizedCodeSize}%5d B")
    options.log.debug(f"Optimized code size:   ${assembler.optimizedCodeSize}%5d B")
    options.log.debug(f"Gain:                   ${(100L * (assembler.unoptimizedCodeSize - assembler.optimizedCodeSize) / assembler.unoptimizedCodeSize.toDouble).round}%5d%%")
    options.log.debug(f"Initialized variables: ${assembler.initializedVariablesSize}%5d B")
    result
  }

  //noinspection NameBooleanParameters
  private def parser(errorReporting: Logger): CliParser[Context] = new CliParser[Context] {

    fluff("Main options:", "")

    parameter("-o", "--out").required().placeholder("<file>").action { (p, c) =>
      assertNone(c.outputFileName, "Output already defined")
      c.copy(outputFileName = Some(p))
    }.description("The output file name, without extension.").onWrongNumber(_ => errorReporting.fatalQuit("No output file specified"))

    flag("-s").action { c =>
      c.copy(outputAssembly = true)
    }.description("Generate also the assembly output.")

    flag("-g").action { c =>
      c.copy(outputLabels = true)
    }.description("Generate also the label file.")

    parameter("-t", "--target").placeholder("<platform>").action { (p, c) =>
      assertNone(c.platform, "Platform already defined")
      c.copy(platform = Some(p))
    }.description(s"Target platform, any of:\n${getAllDefaultPlatforms.grouped(10).map(_.mkString(", ")).mkString(",\n")}.")

    parameter("-I", "--include-dir").repeatable().placeholder("<dir>;<dir>;...").action { (paths, c) =>
      val n = paths.split(";")
      c.copy(includePath = c.includePath ++ n)
    }.description("Include paths for modules. If not given, the default path is used: " + getDefaultIncludePath.fold(identity, identity))

    parameter("-r", "--run").placeholder("<program>").action { (p, c) =>
      assertNone(c.runFileName, "Run program already defined")
      c.copy(runFileName = Some(p))
    }.description("Program to run after successful compilation.")

    parameter("-D", "--define").placeholder("<feature>=<value>").action { (p, c) =>
      val tokens = p.split('=')
      if (tokens.length == 2) {
        assertNone(c.features.get(tokens(0)), "Feature already defined")
        try {
          c.copy(features = c.features + (tokens(0) -> tokens(1).toLong))
        } catch {
          case _:java.lang.NumberFormatException =>
            errorReporting.fatal("Invalid syntax for -D option")
        }
      } else {
        errorReporting.fatal("Invalid syntax for -D option")
      }
    }.description("Define a feature value for the preprocessor.")

    boolean("-finput_intel_syntax", "-finput_zilog_syntax").action((c,v) =>
      c.changeFlag(CompilationFlag.UseIntelSyntaxForInput, v)
    ).description("Select syntax for assembly source input.")

    boolean("-foutput_intel_syntax", "-foutput_zilog_syntax").action((c,v) =>
      c.changeFlag(CompilationFlag.UseIntelSyntaxForOutput, v)
    ).description("Select syntax for assembly output.")

    boolean("--syntax=intel", "--syntax=zilog").action((c,v) =>
      c.changeFlag(CompilationFlag.UseIntelSyntaxForInput, v).changeFlag(CompilationFlag.UseIntelSyntaxForOutput, v)
    ).description("Select syntax for assembly input and output.")

    boolean("-fline-numbers", "-fno-line-numbers").action((c,v) =>
      c.changeFlag(CompilationFlag.LineNumbersInAssembly, v)
    ).description("Show source line numbers in assembly.")

    boolean("-fsource-in-asm", "-fno-source-in-asm").action((c,v) =>
      if (v) {
        c.changeFlag(CompilationFlag.SourceInAssembly, true).changeFlag(CompilationFlag.LineNumbersInAssembly, true)
      } else {
        c.changeFlag(CompilationFlag.LineNumbersInAssembly, false)
      }
    ).description("Show source in assembly.")

    endOfFlags("--").description("Marks the end of options.")

    fluff("", "Verbosity options:", "")

    flag("-q", "--quiet").action { c =>
      assertNone(c.verbosity, "Cannot use -v and -q together")
      c.copy(verbosity = Some(-1))
    }.description("Supress all messages except for errors.")

    private val verbose = flag("-v", "--verbose").maxCount(3).action { c =>
      if (c.verbosity.exists(_ < 0)) errorReporting.error("Cannot use -v and -q together", None)
      c.copy(verbosity = Some(1 + c.verbosity.getOrElse(0)))
    }.description("Increase verbosity.")
    flag("-vv").repeatable().action(c => verbose.encounter(verbose.encounter(verbose.encounter(c)))).description("Increase verbosity even more.")
    flag("-vvv").repeatable().action(c => verbose.encounter(verbose.encounter(c))).description("Increase verbosity even more.")

    fluff("", "Code generation options:", "")

    boolean("-fcmos-ops", "-fno-cmos-ops").action { (c, v) =>
      c.changeFlag(CompilationFlag.EmitCmosOpcodes, v)
    }.description("Whether should emit CMOS opcodes.")
    boolean("-f65ce02-ops", "-fno-65ce02-ops").action { (c, v) =>
      c.changeFlag(CompilationFlag.Emit65CE02Opcodes, v)
    }.description("Whether should emit 65CE02 opcodes.")
    boolean("-fhuc6280-ops", "-fno-huc6280-ops").action { (c, v) =>
      c.changeFlag(CompilationFlag.EmitHudsonOpcodes, v)
    }.description("Whether should emit HuC6280 opcodes.")
    flag("-fno-65816-ops").action { c =>
      c.changeFlag(CompilationFlag.EmitEmulation65816Opcodes, b = false)
      c.changeFlag(CompilationFlag.EmitNative65816Opcodes, b = false)
      c.changeFlag(CompilationFlag.ReturnWordsViaAccumulator, b = false)
    }.description("Don't emit 65816 opcodes.")
    flag("-femulation-65816-ops").action { c =>
      c.changeFlag(CompilationFlag.EmitEmulation65816Opcodes, b = true)
      c.changeFlag(CompilationFlag.EmitNative65816Opcodes, b = false)
      c.changeFlag(CompilationFlag.ReturnWordsViaAccumulator, b = false)
    }.description("Emit 65816 opcodes in emulation mode (experimental).")
    flag("-fnative-65816-ops").action { c =>
      c.changeFlag(CompilationFlag.EmitEmulation65816Opcodes, b = true)
      c.changeFlag(CompilationFlag.EmitNative65816Opcodes, b = true)
    }.description("Emit 65816 opcodes in native mode (very experimental and buggy).")
    boolean("-flarge-code", "-fsmall-code").action { (c, v) =>
      c.changeFlag(CompilationFlag.LargeCode, v)
    }.description("Whether should use 24-bit or 16-bit jumps to subroutines (not yet implemented).").hidden()
    boolean("-fillegals", "-fno-illegals").action { (c, v) =>
      c.changeFlag(CompilationFlag.EmitIllegals, v)
    }.description("Whether should emit illegal (undocumented) NMOS opcodes. Requires -O2 or higher to have an effect.")
    flag("-fzp-register=[0-15]").description("Set the size of the zeropage pseudoregister (6502 only).").dummy()
    (0 to 15).foreach(i =>
      flag("-fzp-register="+i).action(c => c.copy(zpRegisterSize = Some(i))).hidden()
    )
    flag("-fzp-register").action { c =>
      c.copy(zpRegisterSize = Some(4))
    }.description("Alias for -fzp-register=4.")
    flag("-fno-zp-register").action { c =>
      c.copy(zpRegisterSize = Some(0))
    }.description("Alias for -fzp-register=0.")
    boolean("-fjmp-fix", "-fno-jmp-fix").action { (c, v) =>
      c.changeFlag(CompilationFlag.PreventJmpIndirectBug, v)
    }.description("Whether should prevent indirect JMP bug on page boundary (6502 only).")
    boolean("-fdecimal-mode", "-fno-decimal-mode").action { (c, v) =>
      c.changeFlag(CompilationFlag.DecimalMode, v)
    }.description("Whether hardware decimal mode should be used (6502 only).")
    boolean("-fvariable-overlap", "-fno-variable-overlap").action { (c, v) =>
      c.changeFlag(CompilationFlag.VariableOverlap, v)
    }.description("Whether variables should overlap if their scopes do not intersect.")
    boolean("-fcompact-dispatch-params", "-fno-compact-dispatch-params").action { (c, v) =>
      c.changeFlag(CompilationFlag.CompactReturnDispatchParams, v)
    }.description("Whether parameter values in return dispatch statements may overlap other objects.")
    boolean("-fbounds-checking", "-fno-bounds-checking").action { (c, v) =>
      c.changeFlag(CompilationFlag.VariableOverlap, v)
    }.description("Whether should insert bounds checking on array access.")
    boolean("-flenient-encoding", "-fno-lenient-encoding").action { (c, v) =>
      c.changeFlag(CompilationFlag.LenientTextEncoding, v)
    }.description("Whether the compiler should replace invalid characters in string literals that use the default encodings.")
    boolean("-fshadow-irq", "-fno-shadow-irq").action { (c, v) =>
      c.changeFlag(CompilationFlag.UseShadowRegistersForInterrupts, v)
    }.description("Whether shadow registers should be used in interrupt routines (Z80 only)")
    flag("-fuse-ix-for-stack").action { c =>
      c.changeFlag(CompilationFlag.UseIxForStack, true).changeFlag(CompilationFlag.UseIyForStack, false)
    }.description("Use IX as base pointer for stack variables (Z80 only)")
    flag("-fuse-iy-for-stack").action { c =>
      c.changeFlag(CompilationFlag.UseIyForStack, true).changeFlag(CompilationFlag.UseIxForStack, false)
    }.description("Use IY as base pointer for stack variables (Z80 only)")
    boolean("-fuse-ix-for-scratch", "-fno-use-ix-for-scratch").action { (c, v) =>
      if (v) {
        c.changeFlag(CompilationFlag.UseIxForScratch, true).changeFlag(CompilationFlag.UseIxForStack, false)
      } else {
        c.changeFlag(CompilationFlag.UseIxForScratch, false)
      }
    }.description("Use IX as base pointer for stack variables (Z80 only)")
    boolean("-fuse-iy-for-scratch", "-fno-use-iy-for-scratch").action { (c, v) =>
      if (v) {
        c.changeFlag(CompilationFlag.UseIyForScratch, true).changeFlag(CompilationFlag.UseIyForStack, false)
      } else {
        c.changeFlag(CompilationFlag.UseIyForScratch, false)
      }
    }.description("Use IY as base pointer for stack variables (Z80 only)")
    flag("-fno-use-index-for-stack").action { c =>
      c.changeFlag(CompilationFlag.UseIyForStack, false).changeFlag(CompilationFlag.UseIxForStack, false)
    }.description("Don't use either IX or IY as base pointer for stack variables (Z80 only)")
    boolean("-fsoftware-stack", "-fno-software-stack").action { (c, v) =>
      c.changeFlag(CompilationFlag.SoftwareStack, v)
    }.description("Use software stack for stack variables (6502 only)")

    fluff("", "Optimization options:", "")


    flag("-O0").action { c =>
      assertNone(c.optimizationLevel, "Optimization level already defined")
      c.copy(optimizationLevel = Some(0))
    }.description("Disable all optimizations.")
    flag("-O").action { c =>
      assertNone(c.optimizationLevel, "Optimization level already defined")
      c.copy(optimizationLevel = Some(1))
    }.description("Optimize code.")
    for (i <- 1 to 9) {
      val f = flag("-O" + i).action { c =>
        assertNone(c.optimizationLevel, "Optimization level already defined")
        c.copy(optimizationLevel = Some(i))
      }.description("Optimize code even more.")
      if (i == 1 || i > 4) f.hidden()
    }
    flag("--inline").action { c =>
      c.changeFlag(CompilationFlag.InlineFunctions, true)
    }.description("Inline functions automatically.").hidden()
    boolean("-finline", "-fno-inline").action { (c, v) =>
      c.changeFlag(CompilationFlag.InlineFunctions, v)
    }.description("Inline functions automatically.")
    flag("--ipo").action { c =>
      c.changeFlag(CompilationFlag.InterproceduralOptimization, true)
    }.description("Interprocedural optimization.").hidden()
    boolean("--fipo", "--fno-ipo").action { (c, v) =>
      c.changeFlag(CompilationFlag.InterproceduralOptimization, v)
    }.description("Interprocedural optimization.").hidden()
    boolean("-fipo", "-fno-ipo").action { (c, v) =>
      c.changeFlag(CompilationFlag.InterproceduralOptimization, v)
    }.description("Interprocedural optimization.")
    boolean("-foptimize-stdlib", "-fno-optimize-stdlib").action { (c, v) =>
      c.changeFlag(CompilationFlag.OptimizeStdlib, v)
    }.description("Optimize standard library calls.")
    flag("-Os", "--size").action { c =>
      c.changeFlag(CompilationFlag.OptimizeForSize, true).
        changeFlag(CompilationFlag.OptimizeForSpeed, false).
        changeFlag(CompilationFlag.OptimizeForSonicSpeed, false)
    }.description("Prefer smaller code even if it is slightly slower (experimental).")
    flag("-Of", "--fast").action { c =>
      c.changeFlag(CompilationFlag.OptimizeForSize, false).
        changeFlag(CompilationFlag.OptimizeForSpeed, true).
        changeFlag(CompilationFlag.OptimizeForSonicSpeed, false)
    }.description("Prefer faster code even if it is slightly bigger (experimental).")
    flag("-Ob", "--blast-processing").action { c =>
      c.changeFlag(CompilationFlag.OptimizeForSize, false).
        changeFlag(CompilationFlag.OptimizeForSpeed, true).
        changeFlag(CompilationFlag.OptimizeForSonicSpeed, true).
        changeFlag(CompilationFlag.InlineFunctions, true)
    }.description("Prefer faster code even if it is much bigger (experimental). Implies -finline.")
    flag("--dangerous-optimizations").action { c =>
      c.changeFlag(CompilationFlag.DangerousOptimizations, true)
    }.description("Use dangerous optimizations (experimental).").hidden()
    boolean("-fdangerous-optimizations", "-fno-dangerous-optimizations").action { (c, v) =>
      c.changeFlag(CompilationFlag.DangerousOptimizations, v)
    }.description("Use dangerous optimizations (experimental). Implies -fipo.")

    fluff("", "Warning options:", "")

    flag("-Wall", "--Wall").action { c =>
      CompilationFlag.allWarnings.foldLeft(c) { (c, f) => c.changeFlag(f, true) }
    }.description("Enable extra warnings.")

    flag("-Wfatal", "--Wfatal").action { c =>
      c.changeFlag(CompilationFlag.FatalWarnings, true)
    }.description("Treat warnings as errors.")

    fluff("", "Other options:", "")

    flag("--single-threaded").action(c =>
      c.changeFlag(CompilationFlag.SingleThreaded, true)
    ).description("Run the compiler in a single thread.")

    flag("--help").action(c => {
      println("millfork version " + BuildInfo.version)
      println(s"Copyright (C) $copyrightYears  Karol Stasiak")
      println("This program comes with ABSOLUTELY NO WARRANTY.")
      println("This is free software, and you are welcome to redistribute it under certain conditions")
      println("You should have received a copy of the GNU General Public License along with this program. If not, see https://www.gnu.org/licenses/")
      println()
      printHelp(20).foreach(println(_))
      assumeStatus(CliStatus.Quit)
      c
    }).description("Display this message.")

    flag("--version").action(c => {
      println("millfork version " + BuildInfo.version)
      assumeStatus(CliStatus.Quit)
      System.exit(0)
      c
    }).description("Print the version and quit.")


    default.action { (p, c) =>
      if (p.startsWith("-")) {
        errorReporting.error(s"Invalid option `$p`", None)
        c
      } else {
        c.copy(inputFileNames = c.inputFileNames :+ p)
      }
    }

    def assertNone[T](value: Option[T], msg: String): Unit = {
      if (value.isDefined) {
        errorReporting.error(msg, None)
      }
    }
  }
}
