ThisBuild / scalaVersion := "3.3.7"
ThisBuild / organization := "org.virtuslab.cf-maven-repo"
ThisBuild / version := sys.env.getOrElse("FLOW_VERSION", "0.1.0")
ThisBuild / versionScheme := Some("early-semver")

lazy val root = (project in file("."))
  .settings(
    name := "greeter-sbt",
    publishMavenStyle := true,
    // Publishes into a plain Maven-layout directory, which the publisher then uploads. sbt never
    // talks to the object store, so it needs no credentials for it.
    publishTo := Some(MavenCache("staged", file(sys.env.getOrElse("STAGE_DIR", (target.value / "staged").getAbsolutePath)))),
    Test / publishArtifact := false
  )
