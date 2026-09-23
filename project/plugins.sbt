addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.5.0")

// Tag-driven publishing to Maven Central: dynver takes the version from the tag, sbt-pgp signs,
// and `ci-release` uploads through sbt 2's own Sonatype staging.
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.12.1")
