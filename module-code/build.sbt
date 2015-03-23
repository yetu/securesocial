import bintray.Keys._
import play.PlayImport.PlayKeys._
import play.PlayScala
import sbt._

name := """securesocial"""

organization := "com.yetu"

scalaVersion := "2.11.5"

PlayKeys.generateRefReverseRouter := false

libraryDependencies ++= Seq(
  cache,
  ws,
  filters,
  "com.typesafe.play.plugins" %% "play-plugins-util" % "2.3.0",
  "com.typesafe.play.plugins" %% "play-plugins-mailer" % "2.3.0",
  "org.mindrot" % "jbcrypt" % "0.3m",
  "org.specs2" %% "specs2" % "2.3.12" % "test",
  "org.mockito" % "mockito-all" % "1.9.5" % "test"
)

resolvers ++= Seq(
  Resolver.typesafeRepo("releases")
)



lazy val root = (project in file(".")).enablePlugins(PlayScala)

publishMavenStyle := true


pomIncludeRepository := { _ => false }


scalacOptions := Seq("-encoding", "UTF-8", "-Xlint", "-deprecation", "-unchecked", "-feature")

// not adding -Xlint:unchecked for now, will do it once I improve the Java API
javacOptions ++= Seq("-source", "1.6", "-target", "1.6", "-encoding", "UTF-8",  "-Xlint:-options")



// ----------- publishing settings -----------------------------------
// http://www.scala-sbt.org/0.13.5/docs/Detailed-Topics/Publishing.html
// -------------------------------------------------------------------


// sbt-release plugin settings:
releaseSettings

publishMavenStyle := true

// settings for bintray publishing

bintrayPublishSettings

repository in bintray := "maven"

licenses += ("Apache-2.0", url("http://opensource.org/licenses/Apache-2.0"))

packageLabels in bintray := Seq("oauth2", "yetu")

bintrayOrganization in bintray := Some("yetu")

