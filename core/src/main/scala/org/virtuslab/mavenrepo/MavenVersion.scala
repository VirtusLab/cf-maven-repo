package org.virtuslab.mavenrepo

import org.apache.maven.artifact.versioning.ComparableVersion

/** Maven version ordering, delegated to Maven's own `ComparableVersion`.
  *
  * Qualifiers fall into two classes either side of the bare release: `alpha`, `beta`, `milestone`, `rc` and `snapshot` sort below it, while
  * `sp` and any unrecognised qualifier sort above it. So `1.0-rc1 < 1.0 < 1.0-foo`.
  */
object MavenVersion:

  given ordering: Ordering[String] =
    (a, b) => ComparableVersion(a).compareTo(ComparableVersion(b))

  def sorted(versions: Iterable[String]): Seq[String] = versions.toVector.sorted(using ordering)

  def latest(versions: Iterable[String]): Option[String] = versions.maxOption(using ordering)

  /** Highest non-snapshot version. Unrecognised qualifiers still count as releases. */
  def release(versions: Iterable[String]): Option[String] =
    versions.filterNot(isSnapshot).maxOption(using ordering)

  def isSnapshot(version: String): Boolean = version.toUpperCase.endsWith("-SNAPSHOT")
