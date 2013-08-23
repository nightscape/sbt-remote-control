package com.typesafe.sbtrc

import com.typesafe.sbtrc.protocol._
import com.typesafe.sbtrc.it._
import java.io.File
import akka.actor._
import akka.pattern._
import akka.dispatch._
import concurrent.duration._
import concurrent.Await
import akka.util.Timeout
import sbt.IO
import java.util.concurrent.TimeoutException
import com.typesafe.sbtrc.protocol.RequestReceivedEvent

/** Ensures that we can make requests and receive responses from our children. */
class CanRunSbt13AtmosProject extends SbtProcessLauncherTest {
  val dummy = utils.makeEmptySbtProject("runAtmos22", "0.13.0-RC5")
  val plugins = new File(dummy, "project/plugins.sbt")
  IO.write(plugins,
    """addSbtPlugin("com.typesafe.sbt" % "sbt-atmos" % "0.2.3")""")
  val build = new File(dummy, "build.sbt")
  IO.write(build,
    """atmosSettings
      
name := "test-app"
      
scalaVersion := "2.10.2"

libraryDependencies += "com.typesafe.akka" %% "akka-actor" % "2.2.0"
""")
  val appSource = new File(dummy, "src/main/scala/Main.scala")
  IO.write(appSource,
    """
      object Main {
         def main(args: Array[String]): Unit = {
           Thread.sleep(60*1000L)
         }
      }
  """)
  val child = SbtProcess(system, dummy, sbtProcessLauncher)
  @volatile var receivedSocketInfo = false
  try {
    val result = concurrent.promise[Response]()
    val testActor = system.actorOf(Props(new Actor with ActorLogging {
      var askedToStop = false
      context.setReceiveTimeout(70.seconds)
      def receive: Receive = {
        // Here we capture the result of the run task.
        case x: RunResponse =>
          result.success(x)
          context stop self

        // Here we capture the output of play start. 
        // TODO - We should validate the port is the one we expect....
        case GenericEvent("atmos:run", "atmosStarted", params) =>
          receivedSocketInfo = params.contains("uri")
          // Now we can manually cancel
          self ! ReceiveTimeout

        // If we haven't received any events in a while, here's what we do.
        case ReceiveTimeout =>
          result.failure(new RuntimeException("Failed to cancel task within timeout!."))
          context stop self
        case log: LogEvent if (log.entry.message.contains("DEBUGME")) =>
          println(log)
        case e: Event =>
        // Ignore all other events, but let them block our receive timeouts...
      }
    }), "can-run-sbt-13-and-atmos")

    val request =
      GenericRequest(sendEvents = true, TaskNames.runAtmos, Map.empty)
    child.tell(RunRequest(sendEvents = true, mainClass = None), testActor)
    Await.result(result.future, timeout.duration) match {
      case RunResponse(success, "run") =>
        if (!receivedSocketInfo)
          throw new AssertionError("did not receive a play socket we can listen on!")
      case whatever => throw new AssertionError("did not get RunResponse got " + whatever)
    }
  } catch {
    case t: TimeoutException if (!receivedSocketInfo) =>
      sys.error("Failed to start play server before timing out!")
  } finally {
    system.stop(child)
  }
}
