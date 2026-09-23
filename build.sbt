val awsSdk = "2.54.7"
val testcontainers = "0.44.1"
val slf4j = "2.0.18"
val jsoniter = "2.40.1"

// 3.3.8 rather than 3.3.7 because that is what resolves anyway: munit asks for it,
// and the build should say the version it actually compiles against.
ThisBuild / scalaVersion := "3.3.8"
ThisBuild / organization := "org.virtuslab"
ThisBuild / organizationName := "VirtusLab"
ThisBuild / organizationHomepage := Some(uri("https://virtuslab.com"))
// No `version` here on purpose: sbt-ci-release derives it from the git tag, and setting one
// would override the tag a release is cut from.
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / licenses := Seq("Apache-2.0" -> uri("https://www.apache.org/licenses/LICENSE-2.0"))
ThisBuild / description := "Publish and manage a Maven repository on S3-compatible object storage."
ThisBuild / homepage := Some(uri("https://github.com/VirtusLab/cf-maven-repo"))
ThisBuild / scmInfo := Some(
  ScmInfo(
    uri("https://github.com/VirtusLab/cf-maven-repo"),
    "scm:git:https://github.com/VirtusLab/cf-maven-repo.git",
    Some("scm:git:git@github.com:VirtusLab/cf-maven-repo.git")
  )
)
ThisBuild / developers := List(
  Developer("lbialy", "Łukasz Biały", "lbialy@virtuslab.com", uri("https://github.com/lbialy"))
)

// The AWS SDK asks for slf4j-api 1.7.36 and the Testcontainers stack for 1.7.30. 2.x is chosen
// deliberately - it is the API the provider in the CLI binds against - and stated here so a
// future dependency cannot move it without this line changing too.
ThisBuild / dependencyOverrides += "org.slf4j" % "slf4j-api" % slf4j

ThisBuild / scalacOptions ++= Seq(
  "-Werror",
  "-Wunused:all",
  "-Wvalue-discard",
  "-deprecation",
  "-feature"
)

lazy val root = (project in file("."))
  .aggregate(core, cloudflare, cli)
  .settings(
    name := "cf-maven-repo",
    publish / skip := true
  )

lazy val core = (project in file("core"))
  .settings(
    name := "cf-maven-repo-core",
    libraryDependencies ++= Seq(
      // Excluding every default HTTP client drops the SDK from 46 transitive deps to ~15;
      // url-connection-client replaces them below. Both apache artifact names are named because
      // the SDK renamed it, and only the name in use is actually excluded.
      ("software.amazon.awssdk" % "s3" % awsSdk)
        .exclude("software.amazon.awssdk", "netty-nio-client")
        .exclude("software.amazon.awssdk", "apache-client")
        .exclude("software.amazon.awssdk", "apache5-client"),
      "software.amazon.awssdk" % "url-connection-client" % awsSdk,
      // Maven's own version comparator; the qualifier ordering rules are too subtle to reimplement.
      ("org.apache.maven" % "maven-artifact" % "3.9.16")
        .exclude("org.codehaus.plexus", "plexus-utils"),
      // Test only: a library that exports an SLF4J provider makes the choice for every
      // application that consumes it. The CLI, being an application, picks one for itself.
      "org.slf4j" % "slf4j-nop" % slf4j % Test,
      "org.scalameta" %% "munit" % "1.3.5" % Test,
      // The S3 contract is checked against a real implementation; the suite starts its own
      // container and skips itself when there is no Docker to start it in.
      "com.dimafeng" %% "testcontainers-scala-munit" % testcontainers % Test,
      "com.dimafeng" %% "testcontainers-scala-minio" % testcontainers % Test
    )
  )

// Kept out of core so targets without a CDN pull in no HTTP or JSON stack.
lazy val cloudflare = (project in file("cloudflare"))
  .dependsOn(core)
  .settings(
    name := "cf-maven-repo-cloudflare",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.client4" %% "core" % "4.0.26",
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % jsoniter,
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % jsoniter,
      "org.scalameta" %% "munit" % "1.3.5" % Test
    )
  )

lazy val cli = (project in file("cli"))
  .dependsOn(core, cloudflare)
  .settings(
    name := "cf-maven-repo-cli",
    libraryDependencies ++= Seq(
      "com.github.alexarchambault" %% "case-app" % "2.1.0",
      // The API is pinned alongside the provider on purpose. slf4j-nop 2.x binds through the
      // 2.x ServiceLoader mechanism and does nothing against the 1.7.x API that the AWS SDK
      // asks for, so the pairing must be stated rather than left to "highest version wins".
      "org.slf4j" % "slf4j-api" % slf4j,
      "org.slf4j" % "slf4j-nop" % slf4j,
      "org.scalameta" %% "munit" % "1.3.5" % Test
    ),
    // Stated rather than discovered: case-app's two Command objects are main classes too, so
    // discovery finds three and picks none, leaving the published jar without a Main-Class.
    Compile / mainClass := Some("org.virtuslab.mavenrepo.cli.Main"),
    assembly / mainClass := Some("org.virtuslab.mavenrepo.cli.Main"),
    assembly / assemblyJarName := "cf-maven-repo.jar",
    assembly / assemblyMergeStrategy := {
      case PathList(ps @ _*) if ps.lastOption.contains("module-info.class") => MergeStrategy.discard
      case PathList("META-INF", "MANIFEST.MF")                              => MergeStrategy.discard
      // Service loaders must be merged, not picked: the SDK resolves providers through them.
      case PathList("META-INF", "services", _*) => MergeStrategy.concat
      case PathList("META-INF", xs @ _*) if xs.lastOption.exists(n => n.endsWith(".SF") || n.endsWith(".DSA") || n.endsWith(".RSA")) =>
        MergeStrategy.discard
      case _ => MergeStrategy.first
    }
  )
