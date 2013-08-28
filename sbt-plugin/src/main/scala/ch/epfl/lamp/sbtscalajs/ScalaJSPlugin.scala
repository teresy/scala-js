package ch.epfl.lamp.sbtscalajs

import sbt._
import Keys._

import scala.collection.mutable

import SourceMapCat.catJSFilesAndTheirSourceMaps

import com.google.javascript.jscomp.{
  SourceFile => ClosureSource,
  Compiler => ClosureCompiler,
  CompilerOptions => ClosureOptions,
  _
}
import scala.collection.JavaConversions._

object ScalaJSPlugin extends Plugin {
  object ScalaJSKeys {
    val packageJS = TaskKey[File]("package-js")
    val optimizeJS = TaskKey[File]("optimize-js")

    val excludeDefaultScalaLibrary = SettingKey[Boolean]("exclude-default-scala-library")

    val optimizeJSPrettyPrint = SettingKey[Boolean]("optimize-js-pretty-print")
    val optimizeJSExterns = TaskKey[Seq[File]]("optimize-js-externs")
  }

  import ScalaJSKeys._

  private def isJarWithPrefix(prefixes: String*)(item: File): Boolean = {
    item.name.endsWith(".jar") && prefixes.exists(item.name.startsWith)
  }

  private val isScalaLibraryJar = isJarWithPrefix("scala-library") _

  val isScalaJSCompilerJar = isJarWithPrefix(
      "scala-library", "scala-compiler", "scala-reflect", "scalajs-compiler") _

  def sortScalaJSOutputFiles(files: Seq[File]): Seq[File] = {
    files sortWith { (lhs, rhs) =>
      val corejslibName = "scalajs-corejslib.js"
      val lhsName = lhs.name
      val rhsName = rhs.name

      if (rhsName == corejslibName) false
      else if (lhsName == corejslibName) true
      else lhsName.compareTo(rhsName) < 0
    }
  }

  private val ScalaJSExterns = """
    /** @constructor */
    function Object() {}
    Object.protoype.toString = function() {};
    /** @constructor */
    function Array() {}
    Array.prototype.length = 0;
    /** @constructor */
    function Function() {}
    Function.prototype.constructor = function() {};
    Function.prototype.call = function() {};
    Function.prototype.apply = function() {};
    var Math = {
      ceil: function() {},
      floor: function() {}
    }
    """

  val baseScalaJSSettings: Seq[Setting[_]] = Seq(
      // you had better use the same version of Scala as Scala.js
      scalaVersion := "2.10.1",

      excludeDefaultScalaLibrary := false,

      compile in Compile <<= (
          javaHome, streams, cacheDirectory, compileInputs in Compile,
          excludeDefaultScalaLibrary in Compile
      ) map { (javaHome, s, cacheDir, inputs, excludeDefaultScalaLibrary) =>
        import inputs.config._

        val logger = s.log

        // Discover classpaths

        def cpToString(cp: Seq[File]) =
          cp.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator)

        val (compilerCp, cp0) = classpath.partition(isScalaJSCompilerJar)
        val cp =
          if (excludeDefaultScalaLibrary) cp0
          else cp0 ++ compilerCp.filter(isScalaLibraryJar)

        val compilerCpStr = cpToString(compilerCp)
        val cpStr = cpToString(cp)

        // List all my dependencies (recompile if any of these changes)

        val isClassOrJstypeFile = ("*.class": NameFilter) | "*.jstype"
        val allMyDependencies = classpath filterNot (_ == classesDirectory) flatMap { cpFile =>
          if (cpFile.isDirectory) (cpFile ** isClassOrJstypeFile).get
          else Seq(cpFile)
        }

        // Compile

        val cachedCompile = FileFunction.cached(cacheDir / "compile-js",
            FilesInfo.lastModified, FilesInfo.exists) { dependencies =>

          logger.info(
              "Compiling %d Scala.js sources to %s..." format (
              sources.size, classesDirectory))

          if (classesDirectory.exists)
            IO.delete(classesDirectory)
          IO.createDirectory(classesDirectory)

          def doCompileJS(sourcesArgs: List[String]) = {
            Run.executeTrapExit({
              classesDirectory.mkdir()

              Fork.java(javaHome,
                  "-cp" :: compilerCpStr ::
                  "-Xmx512M" ::
                  "scala.tools.nsc.scalajs.Main" ::
                  "-cp" :: cpStr ::
                  "-d" :: classesDirectory.getAbsolutePath() ::
                  options ++:
                  sourcesArgs,
                  logger)
            }, logger)
          }

          val sourcesArgs = sources.map(_.getAbsolutePath()).toList

          /* Crude way of overcoming the Windows limitation on command line
           * length.
           */
          if ((System.getProperty("os.name").toLowerCase().indexOf("win") >= 0) &&
              (sourcesArgs.map(_.length).sum > 1536)) {
            IO.withTemporaryFile("sourcesargs", ".txt") { sourceListFile =>
              IO.writeLines(sourceListFile, sourcesArgs)
              doCompileJS(List("@"+sourceListFile.getAbsolutePath()))
            }
          } else {
            doCompileJS(sourcesArgs)
          }

          // Output is all files in classesDirectory
          (classesDirectory ** AllPassFilter).get.toSet
        }

        cachedCompile((sources ++ allMyDependencies).toSet)

        // We do not have dependency analysis for Scala.js code
        sbt.inc.Analysis.Empty
      },

      unmanagedSources in (Compile, packageJS) := Seq(),

      managedSources in (Compile, packageJS) <<= (
          streams, fullClasspath in Compile, cacheDirectory in Compile
      ) map { (s, fullCp, topCacheDir) =>
        val taskCacheDir = topCacheDir / "package-js"
        IO.createDirectory(taskCacheDir)

        val taskExtractDir = taskCacheDir / "extracted-jars"
        IO.createDirectory(taskExtractDir)

        def fileID(file: File) =
          file.name + "-" + Integer.toString(file.getPath.##, 16)

        // List cp directories, and jars to extract and where

        val cpDirectories = new mutable.ListBuffer[File]
        val jars = mutable.Set.empty[File]

        for (cpFile <- fullCp.map(_.data)) {
          if (cpFile.isDirectory) {
            cpDirectories += cpFile
          } else if (cpFile.isFile && !isScalaJSCompilerJar(cpFile)) {
            val extractDir = taskExtractDir / fileID(cpFile)
            jars += cpFile
            cpDirectories += extractDir
          }
        }

        // Extract jars

        val cachedExtractJars = FileFunction.cached(taskCacheDir / "extract-jars")(
            FilesInfo.lastModified, FilesInfo.exists) { (inReport, outReport) =>

          val usefulFilesFilter = ("*.js": NameFilter) | ("*.js.map")

          for (jar <- inReport.modified -- inReport.removed) {
            s.log.info("Extracting %s ..." format jar)
            val extractDir = taskExtractDir / fileID(jar)
            if (extractDir.exists)
              IO.delete(extractDir)

            IO.createDirectory(extractDir)
            IO.unzip(jar, extractDir, filter = usefulFilesFilter,
                preserveLastModified = true)
          }

          for (jar <- inReport.removed) {
            val extractDir = taskExtractDir / fileID(jar)
            if (extractDir.exists)
              IO.delete(extractDir)
          }

          (taskExtractDir ** usefulFilesFilter).get.toSet
        }

        cachedExtractJars(jars.toSet)

        // List input files (files in earlier dirs shadow files in later dirs)

        val existingPaths = mutable.Set.empty[String]
        val inputs = new mutable.ListBuffer[File]

        for (dir <- cpDirectories) {
          for (file <- (dir ** "*.js").get) {
            val path = IO.relativize(dir, file).get
            if (!existingPaths.contains(path)) {
              inputs += file
              existingPaths += path
            }
          }
        }

        // Return a sorted list of the inputs

        sortScalaJSOutputFiles(inputs.result())
      },

      sources in (Compile, packageJS) <<= (
          managedSources in (Compile, packageJS),
          unmanagedSources in (Compile, packageJS)
      ) map { (managed, unmanaged) =>
        managed ++ unmanaged
      },

      packageJS in Compile <<= (
          streams, cacheDirectory in Compile,
          sources in (Compile, packageJS),
          crossTarget in Compile, moduleName
      ) map { (s, cacheDir, inputs, target, modName) =>
        val output = target / (modName + ".js")
        val taskCacheDir = cacheDir / "package-js"

        val cachedPackage = FileFunction.cached(taskCacheDir / "package",
            FilesInfo.lastModified, FilesInfo.exists) { dependencies =>
          s.log.info("Packaging %s ..." format output)
          catJSFilesAndTheirSourceMaps(inputs, output)
          Set(output)
        }

        cachedPackage(inputs.toSet)

        output
      },

      // TODO Leverage the sbt-js plugin from Untyped

      unmanagedSources in (Compile, optimizeJS) := Seq(),

      managedSources in (Compile, optimizeJS) := Seq(),

      sources in (Compile, optimizeJS) <<= (
          sources in (Compile, packageJS),
          managedSources in (Compile, optimizeJS),
          unmanagedSources in (Compile, optimizeJS)
      ) map { (inputs, managed, unmanaged) =>
        inputs ++ managed ++ unmanaged
      },

      optimizeJSPrettyPrint := false,

      optimizeJSExterns := Seq(),

      optimizeJS in Compile <<= (
          streams, cacheDirectory, sources in (Compile, optimizeJS),
          optimizeJSPrettyPrint in Compile, optimizeJSExterns in Compile,
          crossTarget in Compile, moduleName
      ) map { (s, cacheDir, allJSFiles, prettyPrint, externs, target, modName) =>
        val logger = s.log
        val output = target / (modName + "-opt.js")

        val cachedOptimizeJS = FileFunction.cached(cacheDir / "optimize-js",
            FilesInfo.lastModified, FilesInfo.exists) { dependencies =>

          logger.info("Optimizing %s ..." format output)

          val closureSources = allJSFiles map ClosureSource.fromFile
          val closureExterns = (
              ClosureSource.fromCode("ScalaJSExterns.js", ScalaJSExterns) +:
              externs.map(ClosureSource.fromFile(_)))

          IO.createDirectory(new File(output.getParent))

          val options = new ClosureOptions
          options.prettyPrint = prettyPrint
          CompilationLevel.ADVANCED_OPTIMIZATIONS.setOptionsForCompilationLevel(options)
          options.setLanguageIn(ClosureOptions.LanguageMode.ECMASCRIPT5)

          val compiler = new ClosureCompiler
          val result = compiler.compile(
              closureExterns, closureSources, options)

          val errors = result.errors.toList
          val warnings = result.warnings.toList

          if (!errors.isEmpty) {
            logger.error(errors.length + " Closure errors:")
            errors.foreach(err => logger.error(err.toString))

            IO.write(output, "")
          } else {
            if (!warnings.isEmpty) {
              logger.warn(warnings.length + " Closure warnings:")
              warnings.foreach(err => logger.warn(err.toString))
            }

            IO.write(output, compiler.toSource)
          }

          Set(output)
        }

        cachedOptimizeJS(allJSFiles.toSet)

        output
      }
  )

  val scalaJSSettings: Seq[Setting[_]] = baseScalaJSSettings ++ Seq(
      // you had better use the same version of Scala as Scala.js
      scalaVersion := "2.10.1",

      // you will need the Scala.js compiler on the classpath
      libraryDependencies += "ch.epfl.lamp" %% "scalajs-compiler" % "0.1-SNAPSHOT",

      // and of course the Scala.js library
      libraryDependencies += "ch.epfl.lamp" %% "scalajs-library" % "0.1-SNAPSHOT"
  )
}