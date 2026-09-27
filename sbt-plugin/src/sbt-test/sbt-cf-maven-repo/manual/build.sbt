ThisBuild / organization := "org.example.manual"
ThisBuild / version := "1.0.0"
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / scalaVersion := "3.3.8"

ThisBuild / cfMavenRepoBucket := Checks.bucket
ThisBuild / cfMavenRepoEndpoint := Some(Checks.endpoint)
ThisBuild / cfMavenRepoPathStyle := true

// Staging by hand, as with sbt's own localStaging: publish, then upload.
ThisBuild / publishTo := cfMavenRepoStaging.value

lazy val a = project
lazy val b = project.dependsOn(a)

lazy val root = (project in file("."))
  .aggregate(a, b)
  .settings(
    publish / skip := true,
    commands ++= Checks.commands
  )
