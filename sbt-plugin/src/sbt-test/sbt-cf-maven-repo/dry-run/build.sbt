ThisBuild / organization := "org.example.dryrun"
ThisBuild / version := "1.0.0"
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / scalaVersion := "3.3.8"

ThisBuild / cfMavenRepoBucket := Checks.bucket
ThisBuild / cfMavenRepoEndpoint := Some(Checks.endpoint)
ThisBuild / cfMavenRepoPathStyle := true

ThisBuild / cfMavenRepoDryRun := true

lazy val a = project
lazy val b = project.dependsOn(a)

lazy val root = (project in file("."))
  .aggregate(a, b)
  .settings(
    publish / skip := true,
    commands ++= Checks.commands
  )
