package org.virtuslab.mavenrepo

class MavenVersionSuite extends munit.FunSuite:

  private val cases = List(
    ("7.42.0-core.0.6", "7.42.0", 1), // unrecognised qualifier sorts above the release
    ("7.42.0-core.0.5", "7.42.0-core.0.6", -1),
    ("6.9.1-core.0.5", "6.20.0-core.0.5", -1), // numeric, not lexical
    ("1.0-alpha", "1.0", -1), // recognised qualifiers sort below
    ("1.0-beta", "1.0", -1),
    ("1.0-milestone", "1.0", -1),
    ("1.0-SNAPSHOT", "1.0", -1),
    ("1.0-sp", "1.0", 1),
    ("1.0-foo", "1.0", 1),
    ("1.0-final", "1.0", 0), // final/ga/release alias the release itself
    ("0.2.0", "0.10.0", -1)
  )

  cases.foreach { case (a, b, expected) =>
    test(s"$a vs $b") {
      assertEquals(math.signum(MavenVersion.ordering.compare(a, b)), expected)
    }
  }

  test("sorted places an unrecognised qualifier above its bare release") {
    assertEquals(
      MavenVersion.sorted(
        List("7.42.0", "0.1.0", "7.42.0-core.0.6", "1.0.0-SNAPSHOT", "0.10.0", "0.2.0")
      ),
      List("0.1.0", "0.2.0", "0.10.0", "1.0.0-SNAPSHOT", "7.42.0", "7.42.0-core.0.6")
    )
  }

  test("latest is the highest version, release excludes snapshots") {
    val versions = List("1.0.0", "1.1.0", "2.0.0-SNAPSHOT")
    assertEquals(MavenVersion.latest(versions), Some("2.0.0-SNAPSHOT"))
    assertEquals(MavenVersion.release(versions), Some("1.1.0"))
  }

  test("an unrecognised qualifier still counts as a release") {
    val versions = List("7.41.0-core.0.6", "7.42.0-core.0.6")
    assertEquals(MavenVersion.release(versions), Some("7.42.0-core.0.6"))
  }

  test("empty input yields no latest or release") {
    assertEquals(MavenVersion.latest(Nil), None)
    assertEquals(MavenVersion.release(List("1.0-SNAPSHOT")), None)
  }
