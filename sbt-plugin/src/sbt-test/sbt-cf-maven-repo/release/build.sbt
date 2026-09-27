ThisBuild / organization := "org.example.release"
ThisBuild / version := "1.0.0"
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / scalaVersion := "3.3.8"

ThisBuild / cfMavenRepoBucket := Checks.bucket
ThisBuild / cfMavenRepoEndpoint := Some(Checks.endpoint)
ThisBuild / cfMavenRepoPathStyle := true

// The build's own repository, which a release must leave in place whether it succeeds or fails.
ThisBuild / publishTo := Some(Resolver.file("own", file("own-repo")))

lazy val a = project
lazy val b = project.dependsOn(a)

lazy val root = (project in file("."))
  .aggregate(a, b)
  .settings(
    publish / skip := true,
    commands ++= Checks.commands
  )
