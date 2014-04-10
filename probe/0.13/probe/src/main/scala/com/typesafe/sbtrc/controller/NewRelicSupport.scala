package com.typesafe.sbtrc
package controller

import sbt._
import com.typesafe.sbtrc.protocol.ArtifactEntry

object NewRelicSupport {
  def newRelicAgent(state: State): Option[ArtifactEntry] = {
    val (_, classpath: Seq[Attributed[File]]) = Project.extract(state).runTask(Keys.dependencyClasspath in Compile, state)
    (classpath map { file =>
      for {
        id <- file.get(Keys.moduleID.key)
        if id.organization == "com.newrelic.agent.java"
        if id.name == "newrelic-agent"
      } yield ArtifactEntry(id.revision, file.data.getAbsolutePath())
    }).filter(_.isDefined).headOption.getOrElse(None)
  }
}