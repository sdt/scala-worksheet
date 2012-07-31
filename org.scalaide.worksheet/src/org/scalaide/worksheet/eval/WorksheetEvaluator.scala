package org.scalaide.worksheet.eval

import org.eclipse.debug.core.DebugPlugin
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants
import org.eclipse.jdt.launching.JavaRuntime
import org.eclipse.jface.text.IDocument

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileWriter
import java.io.PrintStream

import scala.sys.process.BasicIO
import scala.sys.process.Process
import scala.sys.process.ProcessIO
import scala.tools.eclipse.ScalaPlugin
import scala.tools.eclipse.ScalaProject
import scala.tools.eclipse.buildmanager.sbtintegration.BasicConfiguration
import scala.tools.eclipse.buildmanager.sbtintegration.ScalaCompilerConf
import scala.tools.eclipse.logging.HasLogger
import scala.tools.nsc.CompilerCommand
import scala.tools.nsc.io.AbstractFile
import scala.tools.nsc.io.VirtualDirectory
import scala.tools.nsc.reporters.StoreReporter

/** An evaluator for worksheet documents.
 *
 *  It evaluates the contents of the given document and returns the output of the
 *  instrumented program.
 *
 *  It instantiates the instrumented program in-process, using a different class-loader.
 *  A more advanced evaluator would spawn a new VM, to allow debugging in the future.
 *
 */
class WorksheetEvaluator(scalaProject: ScalaProject, doc: IDocument) extends HasLogger {

  /** Evaluate the instrumented code and return the result of the execution.
   *  It is typically the original source code with comments containing the reulst of
   *  the evaluation of each expression.
   *
   *  @param fullName The full name of the main class
   *  @param instrumented The instrumented source code (typically returned from `askInstrumented`)
   *  @return A right-biased either (right - the success value)
   */
  def eval(fullName: String, instrumented: Array[Char]): Either[EvaluationError, String] = {
    val iSourceName = writeInstrumented(fullName, instrumented)

    // TODO: extract a better API for getting the configuration of the Scala compiler (should be in ScalaProject)
    val conf = new BasicConfiguration(scalaProject, ScalaCompilerConf.deployedInstance, scalaProject.outputFolderLocations :+ ScalaPlugin.plugin.compilerClasses.get)

    val args = conf.buildArguments(List()).toList ++ List("-d", scalaProject.outputFolderLocations.head.toString)
    logger.debug("Compilation arguments: " + args)
    val (vdirOpt, reporter) = compileInstrumented(iSourceName, args)
    if (reporter.hasErrors) {
      Left(CompilationError(reporter))
    } else
      runInstrumented(fullName)
  }

  /** Write instrumented source file to disk.
   *  @param iFullName  The full name of the first top-level object in source
   *  @param iContents  An Array[Char] containing the instrumented source
   *  @return The name of the instrumented source file
   */
  private def writeInstrumented(iFullName: String, iContents: Array[Char]): String = {
    val iSimpleName = iFullName drop ((iFullName lastIndexOf '.') + 1)
    val iSourceName = iSimpleName + "$instrumented.scala"
    val ifile = new FileWriter(iSourceName)
    ifile.write(iContents)
    ifile.close()
    iSourceName
  }

  /** Compile instrumented source file
   *  @param iSourceName The name of the instrumented source file
   *  @param arguments   Further argumenrs to pass to the compiler
   *  @return Optionallu, if no -d option is given, the virtual directory
   *          contained the generated bytecode classes
   */
  private def compileInstrumented(iSourceName: String, arguments: List[String]): (Option[AbstractFile], StoreReporter) = {
    println("compiling " + iSourceName)
    val command = new CompilerCommand(iSourceName :: arguments, println(_))
    val virtualDirectoryOpt =
      if (arguments contains "-d")
        None
      else {
        val vdir = new VirtualDirectory("(memory)", None)
        command.settings.outputDirs setSingleOutput vdir
        Some(vdir)
      }

    val reporter = new StoreReporter()
    val compiler = new scala.tools.nsc.Global(command.settings, reporter)
    val run = new compiler.Run()
    logger.info("compiling: " + command.files)
    run compile command.files

    (virtualDirectoryOpt, reporter)
  }

  /** Launch `mainClass` in a different JVM and return everything on stdout and stderr.
   * 
   *  This implementation uses `scala.sys.process` to launch `java`, which needs to be
   *  on the classpath. It adds the project classpath and its output folders on the VM
   *  classpath.
   */
  def runInstrumented(mainClass: String): Either[EvaluationError, String] =  {
    val baos = new ByteArrayOutputStream
    val outStream = new PrintStream(baos)

    val pio = new ProcessIO(in => (),
                            os => BasicIO.transferFully(os, outStream),
                            es => BasicIO.transferFully(es, outStream))
    
    try {

      val cp = (scalaProject.scalaClasspath.fullClasspath ++ scalaProject.outputFolderLocations.map(_.toFile)).map(_.getAbsolutePath()).mkString("", File.pathSeparator, "")
      val javaCmd = List("java", "-cp") :+ cp :+ mainClass
      
      logger.debug("Running " + javaCmd.mkString("", " ", ""))
      val builder = Process(javaCmd.toArray, Some(scalaProject.underlying.getLocation().toFile))
      val runnerProcess = builder.run(pio)  
      
      val exitCode = runnerProcess.exitValue()
      // wait until the process terminates, and close this console
      logger.debug("Sbt finished with exit code: %d".format(exitCode))
      outStream.close
      Right(baos.toString)
    } catch {
      case e: Throwable => 
        eclipseLog.error("Error launching instrumented code.", e)
        Left(ExecutionError(e))
    }
  }

  val manager = DebugPlugin.getDefault().getLaunchManager()

  private def getLaunchConfig(): ILaunchConfigurationWorkingCopy = {
    
    val confType = manager.getLaunchConfigurationType(IJavaLaunchConfigurationConstants.ID_JAVA_APPLICATION)
    for {
      conf <- manager.getLaunchConfigurations(confType) 
      if conf.getName() == WORKSHEET_LAUNCH_CONFIGURATION
    } conf.delete()
    
    confType.newInstance(null, WORKSHEET_LAUNCH_CONFIGURATION)
  }
  
  private def getVMInstall() {
  
    val jre = JavaRuntime.getDefaultVMInstall()
    
  }
  
  final val WORKSHEET_LAUNCH_CONFIGURATION = "Start Worksheet"
}