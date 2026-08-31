package org.virtuslab.mavenrepo

import java.nio.file.Files
import java.time.Instant

class CoordinatesSuite extends munit.FunSuite:

  private val c = Coordinates("org.virtuslab.besom", "besom-aws_3", "7.42.0-core.0.6")

  test("group dots become slashes") {
    assertEquals(c.groupPath, "org/virtuslab/besom")
    assertEquals(c.artifactPath, "org/virtuslab/besom/besom-aws_3")
    assertEquals(c.versionPath, "org/virtuslab/besom/besom-aws_3/7.42.0-core.0.6")
  }

  test("the pom key is what marks a version as published") {
    assertEquals(
      c.pomPath,
      "org/virtuslab/besom/besom-aws_3/7.42.0-core.0.6/besom-aws_3-7.42.0-core.0.6.pom"
    )
  }

  test("a single-segment groupId has no slashes") {
    assertEquals(Coordinates("flat", "a_3", "1.0").artifactPath, "flat/a_3")
  }

class CachePolicySuite extends munit.FunSuite:

  private val policy = CachePolicy()

  test("artifacts are immutable, metadata is not") {
    assertEquals(policy.cacheControl("releases/o/v/a-1.0.jar"), CachePolicy.DefaultImmutable)
    assertEquals(policy.cacheControl("releases/o/v/a-1.0.pom"), CachePolicy.DefaultImmutable)
    assertEquals(policy.cacheControl("releases/o/v/maven-metadata.xml"), CachePolicy.DefaultMutable)
  }

  test("metadata checksums are mutable too - they change with the file they describe") {
    assertEquals(
      policy.cacheControl("releases/o/v/maven-metadata.xml.sha1"),
      CachePolicy.DefaultMutable
    )
  }

  test("an artifact merely named after metadata does not count as metadata") {
    assertEquals(
      policy.cacheControl("releases/o/maven-metadata-tool/1.0/maven-metadata-tool-1.0.jar"),
      CachePolicy.DefaultImmutable
    )
    assertEquals(
      policy.cacheControl("releases/o/a/1.0/maven-metadata.xml-tool-1.0.jar"),
      CachePolicy.DefaultImmutable
    )
  }

  test("content types are resolved from the extension") {
    assertEquals(policy.contentType("a/b/x.jar"), "application/java-archive")
    assertEquals(policy.contentType("a/b/x.pom"), "text/xml")
    assertEquals(policy.contentType("a/b/x.sha1"), "text/plain")
    assertEquals(policy.contentType("a/b/x.unknown"), "application/octet-stream")
    assertEquals(policy.contentType("a/b/noextension"), "application/octet-stream")
  }

  test("the extension is taken from the file name, never from the rest of the path") {
    // A dot in a directory must not be mistaken for the file's own extension.
    assertEquals(policy.contentType("a.jar/b/plainfile"), "application/octet-stream")
    assertEquals(policy.contentType("group.id/artifact/1.0/artifact-1.0.pom"), "text/xml")
    // No slash at all: lastIndexOf returns -1 and the whole key is the name.
    assertEquals(policy.contentType("x.jar"), "application/java-archive")
    // A leading dot is an extension, a trailing one is not.
    assertEquals(policy.contentType(".jar"), "application/java-archive")
    assertEquals(policy.contentType("a/b/x."), "application/octet-stream")
    assertEquals(policy.contentType(""), "application/octet-stream")
    // The lookup is case-insensitive, the table is lower case.
    assertEquals(policy.contentType("a/b/X.JAR"), "application/java-archive")
  }

  test("isMetadataKey matches the whole file name and its sidecars, and nothing else") {
    assert(CachePolicy.isMetadataKey("maven-metadata.xml"), "no directory at all")
    assert(CachePolicy.isMetadataKey("o/a/maven-metadata.xml"))
    assert(CachePolicy.isMetadataKey("o/a/maven-metadata.xml.sha512"))
    assert(CachePolicy.isMetadataKey("o/a/maven-metadata.xml.md5"))
    // Neither a longer name nor a longer suffix counts.
    assert(!CachePolicy.isMetadataKey("o/a/maven-metadata.xmlx"))
    assert(!CachePolicy.isMetadataKey("o/a/xmaven-metadata.xml"))
    assert(!CachePolicy.isMetadataKey("o/a/maven-metadata.xml.sha1x"))
    // A directory of that name is not a file of that name.
    assert(!CachePolicy.isMetadataKey("o/maven-metadata.xml/a-1.0.jar"))
    assert(!CachePolicy.isMetadataKey("o/a/maven-metadata.xml/"))
    assert(!CachePolicy.isMetadataKey(""))
  }

  test("a caller can declare extra mutable prefixes without touching the defaults") {
    val custom = CachePolicy(isMutable = key => CachePolicy.isMetadataKey(key) || key.startsWith("index/"))
    assertEquals(custom.cacheControl("index/pkg/1.0/manifest.json"), CachePolicy.DefaultMutable)
    assertEquals(custom.cacheControl("releases/o/v/a-1.0.jar"), CachePolicy.DefaultImmutable)
  }

class ChecksumsSuite extends munit.FunSuite:

  private val bytes = "hello".getBytes("UTF-8")

  test("digests match the well-known values for 'hello'") {
    assertEquals(Checksums.hex(Checksums.Algorithm.Sha1, bytes), "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d")
    assertEquals(Checksums.hex(Checksums.Algorithm.Md5, bytes), "5d41402abc4b2a76b9719d911017c592")
  }

  test("checksum files are the bare hex digest") {
    assertEquals(String(Checksums.content(Checksums.Algorithm.Md5, bytes), "UTF-8").trim, Checksums.hex(Checksums.Algorithm.Md5, bytes))
  }

  test("a file is digested once for every algorithm at once") {
    val file = Files.createTempFile("artifact", ".jar")
    Files.write(file, bytes).discard
    val digests = Checksums.ofFile(file, Checksums.ForArtifacts)
    assertEquals(digests(Checksums.Algorithm.Sha1), Checksums.hex(Checksums.Algorithm.Sha1, bytes))
    assertEquals(digests(Checksums.Algorithm.Md5), Checksums.hex(Checksums.Algorithm.Md5, bytes))
    assertEquals(Checksums.ofFile(file, Nil), Map.empty)
  }

  test("a sidecar yields its digest whatever else the line holds") {
    assertEquals(Checksums.parse("ABC123\n"), Some("abc123"))
    assertEquals(Checksums.parse("abc123  artifact-1.0.jar\n"), Some("abc123"))
    assertEquals(Checksums.parse("   \n"), None)
  }

  test("sidecars are recognised by suffix") {
    assert(Checksums.isChecksum("a-1.0.jar.sha1"))
    assert(Checksums.isChecksum("maven-metadata.xml.sha512"))
    assert(!Checksums.isChecksum("a-1.0.jar"))
    assert(!Checksums.isChecksum("a-1.0-sources.jar"))
  }

class MavenMetadataSuite extends munit.FunSuite:

  test("renders versions in Maven order with latest and release") {
    val xml = MavenMetadata.render(
      "org.virtuslab.besom",
      "besom-aws_3",
      List("7.42.0-core.0.6", "7.41.0-core.0.6"),
      Instant.parse("2026-08-30T12:00:00Z")
    )
    assert(xml.contains("<groupId>org.virtuslab.besom</groupId>"))
    assert(xml.contains("<latest>7.42.0-core.0.6</latest>"))
    assert(xml.contains("<release>7.42.0-core.0.6</release>"))
    assert(xml.contains("<lastUpdated>20260830120000</lastUpdated>"))
    assert(
      xml.indexOf("<version>7.41.0-core.0.6</version>") <
        xml.indexOf("<version>7.42.0-core.0.6</version>")
    )
  }

  test("a snapshot can be latest but never release") {
    val xml = MavenMetadata.render("g", "a", List("1.0", "2.0-SNAPSHOT"))
    assert(xml.contains("<latest>2.0-SNAPSHOT</latest>"))
    assert(xml.contains("<release>1.0</release>"))
  }

  test("no release element when every version is a snapshot") {
    val xml = MavenMetadata.render("g", "a", List("1.0-SNAPSHOT"))
    assert(xml.contains("<latest>1.0-SNAPSHOT</latest>"))
    assert(!xml.contains("<release>"))
  }

  test("markup in coordinates is escaped") {
    assert(MavenMetadata.render("a&b", "c", List("1.0")).contains("<groupId>a&amp;b</groupId>"))
  }

class StagingLayoutSuite extends munit.FunSuite:

  private def stage(entries: (String, String)*) =
    val root = Files.createTempDirectory("staging")
    entries.foreach { case (rel, content) =>
      val p = root.resolve(rel)
      Files.createDirectories(p.getParent)
      Files.writeString(p, content)
      ()
    }
    root

  test("a directory holding a pom is a version, and its path gives the coordinates") {
    val root = stage(
      "org/virtuslab/besom/poc/hello_3/0.2.0/hello_3-0.2.0.pom" -> "<project/>",
      "org/virtuslab/besom/poc/hello_3/0.2.0/hello_3-0.2.0.jar" -> "x",
      "org/virtuslab/besom/poc/hello_3/0.2.0/hello_3-0.2.0.jar.sha1" -> "d"
    )
    val found = StagingLayout.discover(root).fold(bad => fail(s"unexpected $bad"), identity)
    assertEquals(found.size, 1)
    assertEquals(found.head.coordinates, Coordinates("org.virtuslab.besom.poc", "hello_3", "0.2.0"))
    assertEquals(found.head.artifacts.size, 2) // pom + jar
    assertEquals(found.head.checksums.size, 1)
  }

  test("several artifacts and versions are all discovered") {
    val root = stage(
      "o/a_3/1.0/a_3-1.0.pom" -> "x",
      "o/a_3/2.0/a_3-2.0.pom" -> "x",
      "o/b_3/1.0/b_3-1.0.pom" -> "x"
    )
    assertEquals(
      StagingLayout.discover(root).map(_.map(_.coordinates.toString).sorted),
      Right(List("o:a_3:1.0", "o:a_3:2.0", "o:b_3:1.0"))
    )
  }

  test("a tree with no poms yields nothing, and a missing root does not throw") {
    assertEquals(StagingLayout.discover(stage("o/a_3/1.0/notes.txt" -> "x")), Right(Seq.empty))
    assertEquals(StagingLayout.discover(Files.createTempDirectory("empty")), Right(Seq.empty))
    assertEquals(StagingLayout.discover(java.nio.file.Path.of("/nope/nothing/here")), Right(Seq.empty))
  }

class RetrySuite extends munit.FunSuite:

  private val fast = TestRetry.policy(3)

  /** Regression: `Retry` signals success with `boundary.break`, which throws, and that throw is `NonFatal`. Catching it in the same `try`
    * that guards the operation made a successful call look like a failure and ran it again.
    */
  test("an operation that succeeds runs exactly once") {
    var calls = 0
    val result = Retry(fast, TestRetry.anything) { calls += 1; "ok" }
    assertEquals(result, "ok")
    assertEquals(calls, 1)
  }

  test("a transient failure is retried until it clears") {
    var calls = 0
    val result = Retry(fast, TestRetry.anything) {
      calls += 1
      if calls < 3 then throw java.io.IOException("not yet")
      calls
    }
    assertEquals(result, 3)
  }

  test("a failure the classifier rejects is thrown on the first attempt") {
    var calls = 0
    val thrown = intercept[java.io.IOException] {
      Retry(fast, _ => false) { calls += 1; throw java.io.IOException("permanent") }
    }
    assertEquals(thrown.getMessage, "permanent")
    assertEquals(calls, 1)
  }

  test("a failure that never clears gives up after maxRetries, and rethrows the last one") {
    var calls = 0
    val thrown = intercept[java.io.IOException] {
      Retry(TestRetry.policy(2), TestRetry.anything) {
        calls += 1
        throw java.io.IOException(s"attempt $calls")
      }
    }
    // The first attempt plus two retries.
    assertEquals(calls, 3)
    assertEquals(thrown.getMessage, "attempt 3")
  }

  test("a policy with no retries attempts once") {
    var calls = 0
    intercept[java.io.IOException] {
      Retry(Retry.Policy.never, TestRetry.anything) { calls += 1; throw java.io.IOException("x") }
    }
    assertEquals(calls, 1)
  }

  test("backoff doubles from the initial delay and then holds at the cap") {
    val policy = Retry.Policy(maxRetries = 10, initialBackoffMillis = 1000L, maxBackoffMillis = 16000L)
    assertEquals((1 to 7).map(policy.backoffMillis).toList, List(1000L, 2000L, 4000L, 8000L, 16000L, 16000L, 16000L))
  }
