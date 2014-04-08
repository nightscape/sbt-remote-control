package com.typesafe.sbtrc
package controller

import sbt._
import SbtUtil.extract

object NewRelicSupport {
  def newRelicAgent(state: State): Option[String] = {
    val (_, classpath: Seq[Attributed[File]]) = extract(state).runTask(Keys.dependencyClasspath in Compile, state)
    (classpath map { file =>
      for {
        id <- file.get(Keys.moduleID.key)
        if id.organization == "com.newrelic.agent.java"
        if id.name == "newrelic-agent"
      } yield id.revision
    }).filter(_.isDefined).headOption.getOrElse(None)
  }
}