package com.typesafe.sbtrc
package api

import java.net.URI
import protocol.{
  AttributeKey,
  TypeInfo,
  ScopedKey, // Warning here is wrong.  We're pulling in the object.
  SbtScope
}
import java.io.File
import sbt.Attributed

// TODO - This kind of needs to line up with sbt versions.
// Maybe this should be in some kind of compatibility layer or soemthing?
object RemoteKeys {
  private def attributeKey[T](name: String)(implicit mf: Manifest[T]): ScopedKey =
    ScopedKey(
      AttributeKey(name, TypeInfo.fromManifest(mf)),
      SbtScope())
  def settingKey[T](name: String)(implicit mf: Manifest[T]): api.SettingKey[T] =
    SettingKey[T](attributeKey(name))
  def taskKey[T](name: String)(implicit mf: Manifest[T]): api.TaskKey[T] =
    TaskKey[T](attributeKey[T](name))

  val name = settingKey[String]("name")
  val fullClasspath = taskKey[Seq[Attributed[File]]]("fullClasspath")

}
// A set of default configurations we can try.
// TODO - This needs to line up with real configurations to be useful.
object RemoteConfigurations {
  val Compile = "compile"
  val Test = "test"
  val Runtime = "runtime"
}