import bintray.Keys._
import sbt._

name := """securesocial-parent"""

organization := "com.yetu"

version := Common.version

scalaVersion := Common.scalaVersion

lazy val core =  project.in( file("module-code") ).enablePlugins(PlayScala)

lazy val root = project.in( file(".") ).aggregate(core) .settings(
     aggregate in update := false
   )

// ----------- publishing settings -----------------------------------
// http://www.scala-sbt.org/0.13.5/docs/Detailed-Topics/Publishing.html
// -------------------------------------------------------------------


// sbt-release plugin settings:
releaseSettings

publishMavenStyle := true

publishArtifact in (Test, packageBin) := true

// settings for bintray publishing

bintrayPublishSettings

repository in bintray := "maven"

licenses += ("Apache-2.0", url("http://opensource.org/licenses/Apache-2.0"))

packageLabels in bintray := Seq("oauth2", "yetu")

bintrayOrganization in bintray := Some("yetu")