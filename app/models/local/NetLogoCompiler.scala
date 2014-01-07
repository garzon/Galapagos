package models.local

import
  org.nlogo.{ api, compile, nvm, shape, tortoise, workspace },
    compile.front.FrontEnd,
    api.{ AgentKind, CompilerException, ModelReader, ModelSection, Program, ShapeList, WorldDimensions },
    workspace.WidgetParser,
    nvm.{ DefaultParserServices, FrontEndInterface },
      FrontEndInterface.{ NoProcedures, ProceduresMap },
    shape.{ LinkShape, VectorShape },
    tortoise.Compiler

import
  play.api.Logger

case class NetLogoCompiler(iGlobals:     Seq[String]     = Seq(),
                           iGlobalCmds:  String          = "",
                           dimensions:   WorldDimensions = WorldDimensions(-16, 16, -16, 16),
                           turtleShapes: ShapeList       = new ShapeList(AgentKind.Turtle, Seq(VectorShape.getDefaultShape)),
                           linkShapes:   ShapeList       = new ShapeList(AgentKind.Link,   Seq(LinkShape.getDefaultLinkShape)),
                           program:      Program         = Program.empty(),
                           procedures:   ProceduresMap   = NoProcedures) {

  def runCommand(command: String): (NetLogoCompiler, String) = {
    Logger.info(s"Compiling: $command")
    val strOpt = carefullyCompile(Compiler.compileCommands(command, procedures, program))
    Logger.info(s"Compiled to: $strOpt")
    val js = strOpt getOrElse ""
    (this, js)
  }

  def runReporter(reporter: String): (NetLogoCompiler, String) = {
    Logger.info(s"Compiling: $reporter")
    val strOpt = carefullyCompile(Compiler.compileReporter(reporter, procedures, program))
    Logger.info(s"Compiled to: $strOpt")
    val js = strOpt getOrElse ""
    (this, js)
  }

  def runCommand(agentType: String, command: String): (NetLogoCompiler, String) = {
    val cmd =
      if (agentType != "observer")
        s"""|ask $agentType [
            |  $command
            |]""".stripMargin
      else
        command
    runCommand(cmd)
  }

  def apply(source: String): (NetLogoCompiler, String) = {
    Logger.info("Beginning compilation")
    val strCompilerOpt = carefullyCompile {
      val (js, newProgram, newProcedures) =
        Compiler.compileProcedures(source, iGlobals, iGlobalCmds, dimensions, turtleShapes, linkShapes)
      Logger.info("No errors!")
      (this.copy(program = newProgram, procedures = newProcedures), js)
    }
    Logger.info("Compilation complete")
    strCompilerOpt getOrElse ((this, ""))
  }

  // One might be tempted to rewrite this to return a `Try`, but, incidentally, it doesn't really do
  // much for us in this case. --JAB (11/11/13)
  private def carefullyCompile[T](f: => T): Option[T] =
    try Option(f)
    catch {
      case ex: CompilerException =>
        Logger.warn(s"Execution failed: ${ex.getMessage}")
        None
      case ex: MatchError =>
        Logger.warn(s"Could not match given primitive: ${ex.getMessage}")
        None
      case ex: IllegalArgumentException =>
        Logger.warn(s"Feature not yet supported: ${ex.getMessage}")
        None
      case ex: Exception =>
        Logger.warn(s"An unknown exception has occurred: ${ex.getMessage}")
        None
    }

}

object NetLogoCompiler {

  def fromCodeAndDims(source: String, dimensions: (Int, Int, Int, Int)): (NetLogoCompiler, String) = {
    val (minX, maxX, minY, maxY) = dimensions
    NetLogoCompiler(dimensions = WorldDimensions(minX, maxX, minY, maxY))(source)
  }

  def fromNLogoFile(contents: String): (NetLogoCompiler, String) = {

    import collection.JavaConverters.iterableAsScalaIterableConverter

    val modelMap  = ModelReader.parseModel(contents)
    val interface = modelMap(ModelSection.Interface)
    val source    = modelMap(ModelSection.Code).mkString("\n")
    val version   = modelMap(ModelSection.Version).head

    val turtleShapes = {
      val shapeSeq = VectorShape.parseShapes(modelMap(ModelSection.TurtleShapes).toArray, version).asScala.toSeq
      new ShapeList(AgentKind.Turtle, shapeSeq)
    }

    val linkShapes = {
      val shapeSeq = LinkShape.parseShapes(modelMap(ModelSection.LinkShapes).  toArray, version).asScala.toSeq
      new ShapeList(AgentKind.Link, shapeSeq)
    }

    val (iGlobals, _, _, _, iGlobalCmds) = new WidgetParser(new DefaultParserServices(FrontEnd)).parseWidgets(interface)

    val patchSize = interface(7).toDouble
    val Seq(wrapX, wrapY, _, minX, maxX, minY, maxY) = 14 to 20 map { x => interface(x).toInt }
    val dimensions = WorldDimensions(minX, maxX, minY, maxY, patchSize, wrapY != 0, wrapX != 0)

    NetLogoCompiler(iGlobals, iGlobalCmds, dimensions, turtleShapes, linkShapes)(source)

  }

}