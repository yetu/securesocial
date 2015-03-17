name := "SecureSocial-parent"

version := Common.version

scalaVersion := Common.scalaVersion

lazy val core =  project.in( file("module-code") ).enablePlugins(PlayScala)

lazy val root = project.in( file(".") ).aggregate(core) .settings(
     aggregate in update := false
   )
