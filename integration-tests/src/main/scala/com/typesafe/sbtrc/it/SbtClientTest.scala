package com.typesafe.sbtrc
package it

import sbt.client._
import com.typesafe.sbtrc.client.{
  SimpleConnector,
  SimpleLocator,
  LaunchedSbtServerLocator
}
import java.io.File
import java.net.URL
import xsbti.{
  AppConfiguration,
  PredefinedRepository,
  MavenRepository,
  IvyRepository
}
import java.nio.charset.Charset.defaultCharset

trait SbtClientTest extends IntegrationTest {
  // TODO - load from config
  def defaultTimeout = concurrent.duration.Duration(60, java.util.concurrent.TimeUnit.SECONDS)

  /** helper to add error messages when waiting for results and timeouts occur. */
  def waitWithError[T](awaitable: scala.concurrent.Awaitable[T], msg: String): T = {
    try scala.concurrent.Await.result(awaitable, defaultTimeout)
    catch {
      case e: java.util.concurrent.TimeoutException => sys.error(msg)
    }
  }

  def testingLocator(globalDir: File): LaunchedSbtServerLocator = new LaunchedSbtServerLocator {
    // TODO - Do we care if the version for this directory is different?
    def sbtProperties(directory: File): URL =
      rewrittenPropsUrl

    lazy val propsFile: File = {
      val tmp = java.io.File.createTempFile("sbt-server", "properties")
      tmp.deleteOnExit()
      sbt.IO.write(tmp, s"sbt.global.base=${globalDir.toString}")
      tmp
    }

    // Rewrites boot properties for debugging.
    lazy val rewrittenPropsUrl: URL = {
      val tmp = java.io.File.createTempFile("sbt-server", "properties")
      val existing = getClass.getClassLoader.getResource("sbt-server.properties")
      val oldLines = sbt.IO.readLinesURL(existing, defaultCharset)
      val newLines = makeNewLaunchProperties(oldLines)
      sbt.IO.writeLines(tmp, newLines, defaultCharset, false)
      tmp.deleteOnExit()
      tmp.toURI.toURL
    }

    private def repositories: List[String] = {
      configuration.provider.scalaProvider.launcher.ivyRepositories.toList map {
        case ivy: IvyRepository =>
          // TODO - We only support converting things we care about here, not unviersally ok.
          val pattern = Option(ivy.ivyPattern).map(",".+).getOrElse("")
          val aPattern = Option(ivy.artifactPattern).map(",".+).getOrElse("")
          val mvnCompat = if (ivy.mavenCompatible) ", mavenCompatible" else ""
          "  " + ivy.id + ": " + ivy.url + pattern + aPattern + mvnCompat
        case mvn: MavenRepository => "  " + mvn.id + ": " + mvn.url
        case predef: PredefinedRepository => "  " + predef.id.toString
      }
    }

    // TODO - We also need to rewrite the line about the boot directory to use our boot directory.
    private def makeNewLaunchProperties(old: List[String]): List[String] = {
      val header = old.takeWhile(line => !line.contains("[repositories]"))
      val tail = old.dropWhile(line => !line.contains("[boot]")).map {
        // TODO - did we get all the overrides we need?
        case x if x contains "directory:" => s"  directory: ${configuration.provider.scalaProvider.launcher.bootDirectory.toString}"
        case x if x contains "ivy-home:" => s"  ivy-home: ${configuration.provider.scalaProvider.launcher.ivyHome.toString}"
        case x if x contains "override-build-repos:" => "override-build-repos: true"
        case x if x contains "repository-config:" => ""
        case x if x contains "jvmprops:" => s"  jvmprops: ${propsFile.toString}"
        case x => x
      }
      header ++ ("[repositories]" :: repositories) ++ List("") ++ tail
    }
  }

  /**
   * Allows running tests against sbt.  Will block until sbt server is loaded against
   * a given directory...
   *
   * @return the number of connects
   */
  final def withSbt(projectDirectory: java.io.File)(f: SbtClient => Unit): Int = {
    // TODO - Create a prop-file locator that uses our own repositories to
    // find the classes, so we use cached values...
    val connector = new SimpleConnector("sbt-client-test", "SbtClientTest unit test",
      projectDirectory, testingLocator(new File(projectDirectory, "../sbt-global")))

    val numConnects = new java.util.concurrent.atomic.AtomicInteger(0)
    // TODO - Executor for this thread....
    object runOneThingExecutor extends concurrent.ExecutionContext {
      private var task = concurrent.promise[Runnable]
      def execute(runnable: Runnable): Unit = synchronized {
        // We track the number of times our registered connect handler is called here,
        // as we never execute any other future.
        numConnects.getAndIncrement
        // we typically get two runnables; the first one is "newHandler"
        // below and the second is "errorHandler" when the connector is
        // closed. We just drop "errorHandler" on the floor.
        if (task.isCompleted)
          System.err.println(s"Not executing runnable because we only run one thing: ${runnable}")
        else
          task.success(runnable)
      }
      // TODO - Test failure...
      def reportFailure(t: Throwable): Unit = task.failure(t)

      def runWhenReady(): Unit = {
        val result = concurrent.Await.result(task.future, defaultTimeout)
        result.run
      }
    }
    val newHandler: SbtClient => Unit = { client =>
      // TODO - better error reporting than everything.
      (client handleEvents {
        msg => System.out.println(msg)
      })(concurrent.ExecutionContext.global)
      try f(client)
      finally {
        if (!client.isClosed) client.requestExecution("exit", None) // TODO - Should we use the shutdown hook?
      }
    }
    val errorHandler: (Boolean, String) => Unit = { (reconnecting, error) =>
      // don't retry forever just close. But print those errors.
      if (reconnecting) {
        // Only increment here, since we're only doing one thing at a time..
        connector.close()
      } else
        System.err.println(s"sbt connection closed, reconnecting=${reconnecting} error=${error}")
    }

    // TODO - We may want to connect to the sbt server and dump debugging information/logs.
    val subscription = (connector.open(newHandler, errorHandler))(runOneThingExecutor)
    // Block current thread until we can run the test.
    try runOneThingExecutor.runWhenReady()
    finally connector.close()
    if (numConnects.get <= 0) sys.error("Never connected to sbt server!")
    numConnects.get
  }

  lazy val utils = new TestUtil(new java.io.File("scratch"))
}