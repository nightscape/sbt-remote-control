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

/** Ensures that we can make discover New Relic. */
class CanDiscoverNewRelicPlay22Sbt13Project extends SbtProcessLauncherTest {
  val newRelicAgentVersion = "3.5.1"
  val dummy = utils.makeEmptySbtProject("runPlay22", TestUtil.sbt13TestVersion)
  val plugins = new File(dummy, "project/plugins.sbt")
  // TODO - Abstract out plugin version to test more than one play instance...
  IO.write(plugins,
    """
resolvers += ("typesafe-ivy-releases" at "http://private-repo.typesafe.com/typesafe/releases")

resolvers += ("typesafe-mvn-snapshots" at "http://repo.typesafe.com/typesafe/snapshots")

addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.2.2")""")
  val build = new File(dummy, "project/build.scala")
  IO.write(build,
    s"""
import sbt._
import Keys._
import play.Project._

object ApplicationBuild extends Build {

  val appName = "integrationtest"
  val appVersion = "0.2"

  val appDependencies = Seq()

  val main =
      play.Project(appName, appVersion, appDependencies).
      settings(
      playRunHooks += (
       new play.PlayRunHook {
         override def beforeStarted(): Unit = println("ZOMG STARTING")
         override def afterStarted(addr: java.net.InetSocketAddress): Unit =
           println(s"ZOMG SOCKETS R AT $$addr")
         override def afterStopped(): Unit = println("ZOMG STOPPING")
       }
      ),
      libraryDependencies += "com.newrelic.agent.java" % "newrelic-agent" % "$newRelicAgentVersion",
      resolvers += Resolver.url("typesafe-ivy-snapshots", new URL("http://private-repo.typesafe.com/typesafe/ivy-snapshots"))(Resolver.ivyStylePatterns),
      resolvers += ("typesafe-mvn-snapshots" at "http://private-repo.typesafe.com/typesafe/snapshots")
      )
}
""")
  val appconf = new File(dummy, "conf/application.conf")
  IO.write(appconf,
    """
    """)
  val child = SbtProcess(system, dummy, sbtProcessLauncher)
  @volatile var receivedSocketInfo = false
  @volatile var receivedNameInfo = false
  @volatile var newRelicAgent: Option[Map[String, String]] = None
  try {
    val result = concurrent.promise[Response]()
    val testActor = system.actorOf(Props(new Actor with ActorLogging {
      var askedToStop = false
      context.setReceiveTimeout(120.seconds)

      child ! NameRequest(sendEvents = true)
      child ! RunRequest(sendEvents = true, mainClass = None)

      def receive: Receive = {
        case x: NameResponse =>
          log.debug("Received name response " + x)
          receivedNameInfo =
            x.attributes.getOrElse("hasPlay", false).asInstanceOf[Boolean]
          newRelicAgent = x.attributes.get("newRelicAgent").asInstanceOf[Option[Map[String, String]]]
        // Here we capture the result of the run task.
        case x: RunResponse =>
          result.success(x)
          context stop self

        // Here we capture the output of play start.
        // TODO - We should validate the port is the one we expect....
        case GenericEvent("run", "playServerStarted", params) =>
          receivedSocketInfo = true
          // Now we can manually cancel
          self ! ReceiveTimeout

        // If we haven't received any events in a while, here's what we do.
        case ReceiveTimeout =>
          // First we ask ourselves to stop and wait for the result to come
          // back.  If that takes too long, we explode IN YOUR FACE!
          if (!askedToStop) {
            child ! CancelRequest
            context.setReceiveTimeout(30.seconds)
            askedToStop = true
          } else {
            result.failure(new TimeoutException("Failed to cancel task within timeout!."))
            context stop self
          }
        case _: Event =>
        // Ignore all other events, but let them block our receive timeouts...
      }
    }), "can-run-sbt-13-and-play")
    Await.result(result.future, timeout.duration) match {
      case RunResponse(success, "run") =>
        println("DEBUGME: RunResponse = " + success)
        if (!receivedSocketInfo)
          throw new AssertionError("did not receive a play socket we can listen on!")
        if (!receivedNameInfo)
          throw new AssertionError("Did not discover echo/akka support via name request!")
        newRelicAgent match {
          case None => throw new AssertionError("Unable to retrieve New Relic version info")
          case Some(data) => (data.get("version"), data.get("path")) match {
            case (Some(`newRelicAgentVersion`), Some(_)) => // all good
            case (Some(version), Some(_)) => throw new AssertionError(s"version of agent incorrect.  Found $version expecting $newRelicAgentVersion")
            case (None, Some(_)) => throw new AssertionError(s"Missing New Relic version")
            case (Some(_), None) => throw new AssertionError(s"Missing New Relic artifact path")
            case _ => throw new AssertionError(s"Missing New Relic artifact path and version")
          }
        }
      case whatever => throw new AssertionError("did not get RunResponse got " + whatever)
    }
  } catch {
    case t: TimeoutException if receivedSocketInfo =>
    // Ignore.  It's ok to not be able to cancel for now, since activator just kills the entire
    // sbt instance when it needs to cancel things.
    case t: TimeoutException =>
      sys.error("Failed to start play server before timing out!")
  } finally {
    system.stop(child)
  }
}

/** Ensures that we can make discover missing New Relic. */
class CanDiscoverMissingNewRelicPlay22Sbt13Project extends SbtProcessLauncherTest {
  val newRelicAgentVersion = "3.5.1"
  val dummy = utils.makeEmptySbtProject("runPlay22", TestUtil.sbt13TestVersion)
  val plugins = new File(dummy, "project/plugins.sbt")
  // TODO - Abstract out plugin version to test more than one play instance...
  IO.write(plugins,
    """
resolvers += ("typesafe-ivy-releases" at "http://private-repo.typesafe.com/typesafe/releases")

resolvers += ("typesafe-mvn-snapshots" at "http://repo.typesafe.com/typesafe/snapshots")

addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.2.2")""")
  val build = new File(dummy, "project/build.scala")
  IO.write(build,
    s"""
import sbt._
import Keys._
import play.Project._

object ApplicationBuild extends Build {

  val appName = "integrationtest"
  val appVersion = "0.2"

  val appDependencies = Seq()

  val main =
      play.Project(appName, appVersion, appDependencies).
      settings(
      playRunHooks += (
       new play.PlayRunHook {
         override def beforeStarted(): Unit = println("ZOMG STARTING")
         override def afterStarted(addr: java.net.InetSocketAddress): Unit =
           println(s"ZOMG SOCKETS R AT $$addr")
         override def afterStopped(): Unit = println("ZOMG STOPPING")
       }
      ),
      resolvers += Resolver.url("typesafe-ivy-snapshots", new URL("http://private-repo.typesafe.com/typesafe/ivy-snapshots"))(Resolver.ivyStylePatterns),
      resolvers += ("typesafe-mvn-snapshots" at "http://private-repo.typesafe.com/typesafe/snapshots")
      )
}
""")
  val appconf = new File(dummy, "conf/application.conf")
  IO.write(appconf,
    """
    """)
  val child = SbtProcess(system, dummy, sbtProcessLauncher)
  @volatile var receivedSocketInfo = false
  @volatile var receivedNameInfo = false
  @volatile var newRelicAgent: Option[Map[String, String]] = None
  try {
    val result = concurrent.promise[Response]()
    val testActor = system.actorOf(Props(new Actor with ActorLogging {
      var askedToStop = false
      context.setReceiveTimeout(120.seconds)

      child ! NameRequest(sendEvents = true)
      child ! RunRequest(sendEvents = true, mainClass = None)

      def receive: Receive = {
        case x: NameResponse =>
          log.debug("Received name response " + x)
          receivedNameInfo =
            x.attributes.getOrElse("hasPlay", false).asInstanceOf[Boolean]
          newRelicAgent = x.attributes.get("newRelicAgent").asInstanceOf[Option[Map[String, String]]]
        // Here we capture the result of the run task.
        case x: RunResponse =>
          result.success(x)
          context stop self

        // Here we capture the output of play start.
        // TODO - We should validate the port is the one we expect....
        case GenericEvent("run", "playServerStarted", params) =>
          receivedSocketInfo = true
          // Now we can manually cancel
          self ! ReceiveTimeout

        // If we haven't received any events in a while, here's what we do.
        case ReceiveTimeout =>
          // First we ask ourselves to stop and wait for the result to come
          // back.  If that takes too long, we explode IN YOUR FACE!
          if (!askedToStop) {
            child ! CancelRequest
            context.setReceiveTimeout(30.seconds)
            askedToStop = true
          } else {
            result.failure(new TimeoutException("Failed to cancel task within timeout!."))
            context stop self
          }
        case _: Event =>
        // Ignore all other events, but let them block our receive timeouts...
      }
    }), "can-run-sbt-13-and-play")
    Await.result(result.future, timeout.duration) match {
      case RunResponse(success, "run") =>
        println("DEBUGME: RunResponse = " + success)
        if (!receivedSocketInfo)
          throw new AssertionError("did not receive a play socket we can listen on!")
        if (!receivedNameInfo)
          throw new AssertionError("Did not discover echo/akka support via name request!")
        newRelicAgent match {
          case None => // all good
          case Some(_) => throw new AssertionError("Found New Relic, shouldn't have")
        }
      case whatever => throw new AssertionError("did not get RunResponse got " + whatever)
    }
  } catch {
    case t: TimeoutException if receivedSocketInfo =>
    // Ignore.  It's ok to not be able to cancel for now, since activator just kills the entire
    // sbt instance when it needs to cancel things.
    case t: TimeoutException =>
      sys.error("Failed to start play server before timing out!")
  } finally {
    system.stop(child)
  }
}
