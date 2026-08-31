package org.virtuslab.mavenrepo

import java.util.concurrent.{ExecutorService, Executors}
import java.nio.file.{Files, Path}

class PublisherSuite extends munit.FunSuite:

  /** Ordering assertions need a deterministic upload sequence. */
  private val serial = PublishOptions(parallelism = 1)

  /** A POM has to declare the coordinates it is filed under, so the fixture follows the path. */
  private def pomFor(version: String) =
    s"<project><groupId>org.example</groupId><artifactId>lib_3</artifactId>" +
      s"<version>$version</version></project>"

  private val PomBody = pomFor("1.0.0")
  private val PomSha1 = Checksums.hex(Checksums.Algorithm.Sha1, PomBody.getBytes("UTF-8"))

  private def stage(entries: (String, String)*): Path =
    val root = Files.createTempDirectory("staging")
    entries.foreach { case (rel, content) =>
      val p = root.resolve(rel)
      Files.createDirectories(p.getParent)
      Files.writeString(p, content)
      ()
    }
    root

  private def oneVersion(version: String = "1.0.0") = stage(
    s"org/example/lib_3/$version/lib_3-$version.pom" -> pomFor(version),
    s"org/example/lib_3/$version/lib_3-$version.jar" -> "jar bytes"
  )

  private def published(store: InMemoryObjectStore, version: String = "1.0.0"): Unit =
    val report = Publisher.publish(store, oneVersion(version), PublishTarget(), serial)
    assert(report.isRight, report)

  test("publishes artifacts, generated checksums and metadata") {
    val store = InMemoryObjectStore()
    val report = Publisher.publish(store, oneVersion(), PublishTarget(), serial)

    assert(report.isRight, report)
    val keys = store.keys("")
    assert(keys.contains("releases/org/example/lib_3/1.0.0/lib_3-1.0.0.jar"))
    assert(keys.contains("releases/org/example/lib_3/1.0.0/lib_3-1.0.0.pom"))
    assert(keys.contains("releases/org/example/lib_3/1.0.0/lib_3-1.0.0.jar.sha1"))
    assert(keys.contains("releases/org/example/lib_3/1.0.0/lib_3-1.0.0.jar.md5"))
    assert(keys.contains("releases/org/example/lib_3/maven-metadata.xml"))
    assert(keys.contains("releases/org/example/lib_3/maven-metadata.xml.sha512"))
  }

  test("metadata is written last - it is the commit point") {
    val store = InMemoryObjectStore()
    published(store)

    val log = store.putLog
    val firstMetadata = log.indexWhere(_.contains("maven-metadata.xml"))
    val lastArtifact = log.lastIndexWhere(!_.contains("maven-metadata.xml"))
    assert(firstMetadata > lastArtifact, s"metadata must follow every artifact, got:\n${log.mkString("\n")}")
  }

  test("the pom is written last within a version - it marks the version complete") {
    val store = InMemoryObjectStore()
    published(store)

    val log = store.putLog.filterNot(_.contains("maven-metadata.xml"))
    assertEquals(log.last, "releases/org/example/lib_3/1.0.0/lib_3-1.0.0.pom")
  }

  test("a checksum never follows the artifact it describes") {
    val store = InMemoryObjectStore()
    published(store)

    val log = store.putLog
    assert(
      log.indexOf("releases/org/example/lib_3/1.0.0/lib_3-1.0.0.jar.sha1") <
        log.indexOf("releases/org/example/lib_3/1.0.0/lib_3-1.0.0.jar")
    )
  }

  test("staged checksums are uploaded as-is rather than recomputed") {
    val root = stage(
      "org/example/lib_3/1.0.0/lib_3-1.0.0.pom" -> PomBody,
      "org/example/lib_3/1.0.0/lib_3-1.0.0.pom.sha1" -> s"$PomSha1  lib_3-1.0.0.pom\n"
    )
    val store = InMemoryObjectStore()
    val report = Publisher.publish(store, root, PublishTarget(), serial)
    assert(report.isRight, report)
    assertEquals(store.stringOf("releases/org/example/lib_3/1.0.0/lib_3-1.0.0.pom.sha1"), Some(s"$PomSha1  lib_3-1.0.0.pom\n"))
  }

  test("a staged checksum that does not match its artifact is refused") {
    val root = stage(
      "org/example/lib_3/1.0.0/lib_3-1.0.0.pom" -> PomBody,
      "org/example/lib_3/1.0.0/lib_3-1.0.0.pom.sha1" -> "deadbeef"
    )
    val store = InMemoryObjectStore()
    Publisher.publish(store, root, PublishTarget(), serial) match
      case Left(PublishError.ChecksumMismatch(_, algorithm, expected, actual)) =>
        assertEquals(algorithm, "sha1")
        assertEquals(expected, "deadbeef")
        assertEquals(actual, PomSha1)
      case other => fail(s"expected ChecksumMismatch, got $other")
    assertEquals(store.size, 0)
  }

  test("--trust-staged-checksums uploads a wrong checksum without complaint") {
    val root = stage(
      "org/example/lib_3/1.0.0/lib_3-1.0.0.pom" -> PomBody,
      "org/example/lib_3/1.0.0/lib_3-1.0.0.pom.sha1" -> "deadbeef"
    )
    val store = InMemoryObjectStore()
    val opts = serial.copy(verifyStagedChecksums = false)
    assert(Publisher.publish(store, root, PublishTarget(), opts).isRight)
  }

  test("an empty or missing staging tree is an error, not a silent success") {
    val store = InMemoryObjectStore()
    val empty = Files.createTempDirectory("empty")
    Publisher.publish(store, empty, PublishTarget(), serial) match
      case Left(PublishError.NothingToPublish(root)) => assertEquals(root, empty)
      case other                                     => fail(s"expected NothingToPublish, got $other")

    Publisher.publish(store, Path.of("/nope/nothing/here"), PublishTarget(), serial) match
      case Left(_: PublishError.NothingToPublish) => ()
      case other                                  => fail(s"expected NothingToPublish, got $other")

    assert(Publisher.publish(store, empty, PublishTarget(), serial.copy(allowEmpty = true)).isRight)
  }

  test("a snapshot is refused unless explicitly allowed") {
    val store = InMemoryObjectStore()
    Publisher.publish(store, oneVersion("1.0.0-SNAPSHOT"), PublishTarget(), serial) match
      case Left(PublishError.SnapshotVersion(c)) => assertEquals(c.version, "1.0.0-SNAPSHOT")
      case other                                 => fail(s"expected SnapshotVersion, got $other")

    val opts = serial.copy(allowSnapshots = true)
    assert(Publisher.publish(store, oneVersion("1.0.0-SNAPSHOT"), PublishTarget(), opts).isRight)
  }

  test("republishing an existing version is refused") {
    val store = InMemoryObjectStore()
    published(store)

    Publisher.publish(store, oneVersion(), PublishTarget(), serial) match
      case Left(PublishError.AlreadyPublished(c, _)) => assertEquals(c.version, "1.0.0")
      case other                                     => fail(s"expected AlreadyPublished, got $other")
  }

  test("--skip-existing treats it as done and still rebuilds metadata") {
    val store = InMemoryObjectStore()
    val opts = serial.copy(skipExisting = true)
    Publisher.publish(store, oneVersion(), PublishTarget(), opts).discard
    store.delete("releases/org/example/lib_3/maven-metadata.xml")

    val report = Publisher.publish(store, oneVersion(), PublishTarget(), opts)
    assertEquals(report.map(_.skipped.map(_.version)), Right(Seq("1.0.0")))
    assertEquals(report.map(_.published), Right(Seq.empty))
    assert(store.head("releases/org/example/lib_3/maven-metadata.xml").isDefined)
  }

  test("dry run writes nothing but reports the exact key set and byte count") {
    val store = InMemoryObjectStore()
    val report = Publisher.publish(store, oneVersion(), PublishTarget(), serial.copy(dryRun = true))

    assertEquals(store.size, 0)
    val r = report.toOption.get
    assert(r.uploaded.contains("releases/org/example/lib_3/1.0.0/lib_3-1.0.0.jar"))
    assert(r.bytes > 0)
    assert(r.metadata.contains("releases/org/example/lib_3/maven-metadata.xml"))
  }

  test("metadata lists every version in the store, not just this run's") {
    val store = InMemoryObjectStore()
    published(store, "1.0.0")
    published(store, "2.0.0")

    val xml = store.stringOf("releases/org/example/lib_3/maven-metadata.xml").get
    assert(xml.contains("<version>1.0.0</version>"), xml)
    assert(xml.contains("<version>2.0.0</version>"), xml)
    assert(xml.contains("<latest>2.0.0</latest>"), xml)
  }

  test("metadata lists only versions whose pom landed") {
    val store = InMemoryObjectStore()
    published(store, "1.0.0")
    // What an interrupted run leaves behind: files under a version, but no pom.
    store
      .put(
        "releases/org/example/lib_3/2.0.0/lib_3-2.0.0.jar",
        Body.Bytes("x".getBytes),
        ObjectHeaders("a", "b"),
        Precondition.Unconditional
      )
      .discard

    Publisher.republishMetadata(store, Seq("org/example/lib_3"), PublishTarget()).discard
    val xml = store.stringOf("releases/org/example/lib_3/maven-metadata.xml").get
    assert(xml.contains("<version>1.0.0</version>"), xml)
    assert(!xml.contains("<version>2.0.0</version>"), xml)
  }

  test("a metadata rewrite that lost the race is retried against the new state") {
    val store = InMemoryObjectStore()
    published(store, "1.0.0")

    // Stand in for a second publisher: change the object between the read and the write, once.
    var raced = false
    val racing = new ObjectStore:
      def head(key: String) = store.head(key)
      def put(key: String, body: Body, headers: ObjectHeaders, precondition: Precondition) =
        if key.endsWith(Coordinates.MetadataFileName) && !raced then
          raced = true
          store.put(key, Body.Bytes("interloper".getBytes), headers, Precondition.Unconditional).discard
        store.put(key, body, headers, precondition)
      def childPrefixes(prefix: String) = store.childPrefixes(prefix)
      def keys(prefix: String) = store.keys(prefix)
      def delete(key: String) = store.delete(key)

    val result = Publisher.republishMetadata(racing, Seq("org/example/lib_3"), PublishTarget())
    assert(result.isRight, result)
    assert(raced)
    val xml = store.stringOf("releases/org/example/lib_3/maven-metadata.xml").get
    assert(xml.contains("<version>1.0.0</version>"), xml)
  }

  test("headers follow the cache policy") {
    val store = InMemoryObjectStore()
    published(store)

    assertEquals(
      store.headersOf("releases/org/example/lib_3/1.0.0/lib_3-1.0.0.jar"),
      Some(ObjectHeaders("application/java-archive", CachePolicy.DefaultImmutable))
    )
    assertEquals(
      store.headersOf("releases/org/example/lib_3/maven-metadata.xml"),
      Some(ObjectHeaders("text/xml", CachePolicy.DefaultMutable))
    )
  }

  test("an empty prefix publishes at the root") {
    val store = InMemoryObjectStore()
    Publisher.publish(store, oneVersion(), PublishTarget(prefix = ""), serial).discard
    assert(store.head("org/example/lib_3/1.0.0/lib_3-1.0.0.pom").isDefined)
  }

  test("purge covers metadata and its sidecars, and never an artifact") {
    val store = InMemoryObjectStore()
    var purged = Seq.empty[String]
    val purger: CachePurger = urls => { purged = urls; Right(()) }
    val target = PublishTarget(purger = Some(purger), publicUrl = Some("https://maven.example.com"))

    Publisher.publish(store, oneVersion(), target, serial).discard
    val base = "https://maven.example.com/releases/org/example/lib_3/maven-metadata.xml"
    assertEquals(purged.head, base)
    assertEquals(purged.toSet, (base +: Checksums.ForMetadata.map(a => base + a.suffix)).toSet)
  }

  test("nothing is reported as purged when no purger is configured") {
    val store = InMemoryObjectStore()
    val target = PublishTarget(publicUrl = Some("https://maven.example.com"))
    val report = Publisher.publish(store, oneVersion(), target, serial)
    assertEquals(report.map(_.purged), Right(Seq.empty))
  }

  test("a resumed run skips what the ledger records and the store still holds") {
    val store = InMemoryObjectStore()
    val ledgerPath = Files.createTempDirectory("ledger").resolve("l.txt")
    val root = oneVersion()

    Publisher.publish(store, root, PublishTarget(), serial.copy(ledger = Ledger.open(ledgerPath, "h/b/releases"))).discard
    // What an interruption before the marker looks like: everything present except the POM.
    val pom = "releases/org/example/lib_3/1.0.0/lib_3-1.0.0.pom"
    store.delete(pom)

    val before = store.putLog.size
    Publisher.publish(store, root, PublishTarget(), serial.copy(ledger = Ledger.open(ledgerPath, "h/b/releases"))).discard
    val resumed = store.putLog.drop(before).filterNot(_.contains("maven-metadata.xml"))
    assertEquals(resumed, Seq(pom))
  }

  test("a ledger is not believed over the store when its objects are gone") {
    val ledgerPath = Files.createTempDirectory("ledger").resolve("l.txt")
    val root = oneVersion()

    Publisher.publish(InMemoryObjectStore(), root, PublishTarget(), serial.copy(ledger = Ledger.open(ledgerPath, "h/b/releases"))).discard

    // Same destination, but the objects are no longer there - a wiped bucket, or a failed write.
    val empty = InMemoryObjectStore()
    val report = Publisher.publish(empty, root, PublishTarget(), serial.copy(ledger = Ledger.open(ledgerPath, "h/b/releases")))
    assert(report.isRight, report)
    assert(empty.head("releases/org/example/lib_3/1.0.0/lib_3-1.0.0.jar").isDefined, s"expected a full re-upload, got ${empty.putLog}")
    assert(empty.head("releases/org/example/lib_3/1.0.0/lib_3-1.0.0.pom").isDefined)
  }

  test("metadata and its checksums still describe each other after a lost race") {
    val store = InMemoryObjectStore()
    published(store, "1.0.0")
    val metadataKey = "releases/org/example/lib_3/maven-metadata.xml"

    // A second publisher lands a different version list, plus its own sidecars, in the window
    // between our conditional XML write and our sidecar writes.
    var interfered = false
    val contended = new ObjectStore:
      def head(key: String) = store.head(key)
      def put(key: String, body: Body, headers: ObjectHeaders, precondition: Precondition) =
        val outcome = store.put(key, body, headers, precondition)
        if key == metadataKey && !interfered && outcome == PutOutcome.Written then
          interfered = true
          val theirs = MavenMetadata.render("org.example", "lib_3", List("1.0.0", "9.9.9")).getBytes
          store.put(key, Body.Bytes(theirs), headers, Precondition.Unconditional).discard
          Checksums.ForMetadata.foreach { a =>
            store.put(key + a.suffix, Body.Bytes(Checksums.content(a, theirs)), headers, Precondition.Unconditional).discard
          }
        outcome
      def childPrefixes(prefix: String) = store.childPrefixes(prefix)
      def keys(prefix: String) = store.keys(prefix)
      def delete(key: String) = store.delete(key)
      override def read(key: String) = store.read(key)

    val result = Publisher.republishMetadata(contended, Seq("org/example/lib_3"), PublishTarget())
    assert(result.isRight, result)
    assert(interfered)

    val finalXml = store.bytesOf(metadataKey).get
    Checksums.ForMetadata.foreach { a =>
      assertEquals(
        store.stringOf(metadataKey + a.suffix),
        Some(String(Checksums.content(a, finalXml), "UTF-8")),
        s"${a.suffix} does not describe the metadata beside it"
      )
    }
  }

  test("a ledger written for another destination is discarded, not believed") {
    val store = InMemoryObjectStore()
    val ledgerPath = Files.createTempDirectory("ledger").resolve("l.txt")
    val root = oneVersion()

    Publisher.publish(store, root, PublishTarget(), serial.copy(ledger = Ledger.open(ledgerPath, "one/releases"))).discard

    val elsewhere = InMemoryObjectStore()
    Publisher.publish(elsewhere, root, PublishTarget(), serial.copy(ledger = Ledger.open(ledgerPath, "two/releases"))).discard
    assert(
      elsewhere.head("releases/org/example/lib_3/1.0.0/lib_3-1.0.0.jar").isDefined,
      s"expected a full publish, got ${elsewhere.putLog}"
    )
  }

  /** The version list is recovered by slicing keys around the first slash after the artifact prefix, so every neighbouring shape has to be
    * rejected by that arithmetic rather than by luck.
    */
  test("the version list counts only versions whose own POM is present") {
    val store = InMemoryObjectStore()
    val headers = ObjectHeaders("text/plain", "x")
    val base = "releases/org/example/lib_3"
    def put(rest: String): Unit =
      store.put(s"$base/$rest", Body.Bytes("x".getBytes), headers, Precondition.Unconditional).discard

    put("1.0.0/lib_3-1.0.0.pom") // counts
    put("2.0.0/lib_3-2.0.0.pom") // counts
    put("3.0.0/lib_3-3.0.0.jar") // a version with no POM is half-uploaded, not published
    put("4.0.0/lib_3-4.0.0.pom.sha1") // the sidecar is not the marker
    put("5.0.0/other_3-5.0.0.pom") // a POM belonging to a different artifact
    put("6.0.0/lib_3-9.9.9.pom") // a POM whose version disagrees with its directory
    put("7.0.0/nested/lib_3-7.0.0.pom") // one level deeper than a version directory
    put("maven-metadata.xml") // directly under the prefix, so it has no version segment at all

    val result = Publisher.republishMetadata(store, Seq("org/example/lib_3"), PublishTarget())
    assert(result.isRight, result)
    val xml = store.stringOf(s"$base/maven-metadata.xml").get
    val listed = "<version>([^<]+)</version>".r.findAllMatchIn(xml).map(_.group(1)).toList
    assertEquals(listed, List("1.0.0", "2.0.0"))
  }

  test("republishMetadata rebuilds from store state with no staging tree") {
    val store = InMemoryObjectStore()
    published(store, "1.0.0")
    published(store, "2.0.0")
    store.delete("releases/org/example/lib_3/maven-metadata.xml")

    val artifacts = Publisher.artifactsUnder(store, PublishTarget(), "org.example")
    assertEquals(artifacts, Right(Seq("org/example/lib_3")))

    val result = Publisher.republishMetadata(store, artifacts.toOption.get, PublishTarget())
    assert(result.isRight, result)
    val xml = store.stringOf("releases/org/example/lib_3/maven-metadata.xml").get
    assert(xml.contains("<version>1.0.0</version>") && xml.contains("<version>2.0.0</version>"))
  }

  private def stagedPom(body: String) = stage(
    "org/example/lib_3/1.0.0/lib_3-1.0.0.pom" -> body,
    "org/example/lib_3/1.0.0/lib_3-1.0.0.jar" -> "jar bytes"
  )

  test("a pom claiming coordinates other than its path is refused") {
    val root = stagedPom(
      """<project><groupId>org.example</groupId><artifactId>lib_3</artifactId>
         <version>2.0.0</version></project>"""
    )
    val store = InMemoryObjectStore()
    Publisher.publish(store, root, PublishTarget(), serial) match
      case Left(PublishError.InvalidPom(_, problem)) =>
        assert(problem.contains("version 2.0.0"), problem)
      case other => fail(s"expected InvalidPom, got $other")
    assertEquals(store.size, 0)
  }

  test("coordinates inherited from a parent count as declared") {
    val matching = stagedPom(
      """<project><parent><groupId>org.example</groupId><version>1.0.0</version></parent>
         <artifactId>lib_3</artifactId></project>"""
    )
    assert(Publisher.publish(InMemoryObjectStore(), matching, PublishTarget(), serial).isRight)

    val wrong = stagedPom(
      """<project><parent><groupId>org.other</groupId><version>1.0.0</version></parent>
         <artifactId>lib_3</artifactId></project>"""
    )
    Publisher.publish(InMemoryObjectStore(), wrong, PublishTarget(), serial) match
      case Left(PublishError.InvalidPom(_, problem)) => assert(problem.contains("org.other"), problem)
      case other                                     => fail(s"expected InvalidPom, got $other")
  }

  test("a dependency's own version is not mistaken for the project's") {
    val root = stagedPom(
      """<project><groupId>org.example</groupId><artifactId>lib_3</artifactId>
         <version>1.0.0</version>
         <dependencies><dependency><groupId>x</groupId><artifactId>y</artifactId>
         <version>9.9.9</version></dependency></dependencies></project>"""
    )
    assert(Publisher.publish(InMemoryObjectStore(), root, PublishTarget(), serial).isRight)
  }

  test("a coordinate written as a property is refused, not waved through") {
    val root = stagedPom(
      """<project><groupId>${my.group}</groupId><artifactId>lib_3</artifactId>
         <version>1.0.0</version></project>"""
    )
    Publisher.publish(InMemoryObjectStore(), root, PublishTarget(), serial) match
      case Left(PublishError.InvalidPom(_, problem)) => assert(problem.contains("${my.group}"), problem)
      case other                                     => fail(s"expected InvalidPom, got $other")
  }

  test("a coordinate that is neither declared nor inherited is refused") {
    val root = stagedPom("""<project><artifactId>lib_3</artifactId></project>""")
    Publisher.publish(InMemoryObjectStore(), root, PublishTarget(), serial) match
      case Left(PublishError.InvalidPom(_, problem)) => assert(problem.contains("no groupId"), problem)
      case other                                     => fail(s"expected InvalidPom, got $other")
  }

  /** Maven requires a `<parent>` to name its groupId, artifactId and version literally, so one level of lookup covers every coordinate a
    * POM can inherit.
    */
  test("a parent supplies what the module leaves out, and is itself checked") {
    val root = stagedPom(
      """<project><parent><groupId>org.example</groupId><artifactId>p</artifactId>
         <version>1.0.0</version></parent><artifactId>lib_3</artifactId></project>"""
    )
    assert(Publisher.publish(InMemoryObjectStore(), root, PublishTarget(), serial).isRight)

    val propertyVersion = stagedPom(
      """<project><parent><groupId>org.example</groupId><artifactId>p</artifactId>
         <version>${revision}</version></parent><artifactId>lib_3</artifactId></project>"""
    )
    Publisher.publish(InMemoryObjectStore(), propertyVersion, PublishTarget(), serial) match
      case Left(PublishError.InvalidPom(_, problem)) => assert(problem.contains("${revision}"), problem)
      case other                                     => fail(s"expected InvalidPom, got $other")
  }

  test("a pom that is not XML is refused, and can be forced through") {
    val root = stagedPom("this is not xml <<<")
    Publisher.publish(InMemoryObjectStore(), root, PublishTarget(), serial) match
      case Left(PublishError.InvalidPom(_, problem)) => assert(problem.contains("XML"), problem)
      case other                                     => fail(s"expected InvalidPom, got $other")

    val opts = serial.copy(verifyPomCoordinates = false)
    assert(Publisher.publish(InMemoryObjectStore(), root, PublishTarget(), opts).isRight)
  }

  test("a version staged under a non-canonical pom name is refused") {
    val root = stage(
      "org/example/lib_3/1.0.0/lib_3-1.0.pom" -> PomBody,
      "org/example/lib_3/1.0.0/lib_3-1.0.0.jar" -> "jar bytes"
    )
    val store = InMemoryObjectStore()
    Publisher.publish(store, root, PublishTarget(), serial) match
      case Left(PublishError.MissingPom(_, expected)) => assertEquals(expected, "lib_3-1.0.0.pom")
      case other                                      => fail(s"expected MissingPom, got $other")
    assertEquals(store.size, 0)
  }

  test("a store that reports no ETag cannot rewrite metadata unguarded") {
    val backing = InMemoryObjectStore()
    val eTagless = new ObjectStore:
      def head(key: String) = backing.head(key).map(_.copy(eTag = None))
      def put(key: String, body: Body, headers: ObjectHeaders, precondition: Precondition) =
        backing.put(key, body, headers, precondition)
      def childPrefixes(prefix: String) = backing.childPrefixes(prefix)
      def keys(prefix: String) = backing.keys(prefix)
      def delete(key: String) = backing.delete(key)

    // The first publish writes metadata that did not exist, which needs no ETag.
    assert(Publisher.publish(eTagless, oneVersion("1.0.0"), PublishTarget(), serial).isRight)
    Publisher.publish(eTagless, oneVersion("2.0.0"), PublishTarget(), serial) match
      case Left(PublishError.MissingETag(key)) =>
        assertEquals(key, "releases/org/example/lib_3/maven-metadata.xml")
      case other => fail(s"expected MissingETag, got $other")
  }

  test("a failure the store calls permanent is not retried") {
    var attempts = 0
    val rejecting = new ObjectStore:
      def head(key: String) = None
      def put(key: String, body: Body, headers: ObjectHeaders, precondition: Precondition) =
        attempts += 1
        throw java.io.IOException("access denied")
      def childPrefixes(prefix: String) = Seq.empty
      def keys(prefix: String) = Seq.empty
      def delete(key: String) = ()

    Publisher.publish(rejecting, oneVersion(), PublishTarget(), serial) match
      case Left(_: PublishError.UploadFailed) => assertEquals(attempts, 1)
      case other                              => fail(s"expected UploadFailed, got $other")
  }

  test("listing failures during discovery stay inside the error model") {
    val exploding = new ObjectStore:
      def head(key: String) = None
      def put(key: String, body: Body, headers: ObjectHeaders, precondition: Precondition) =
        PutOutcome.Written
      def childPrefixes(prefix: String) = throw java.io.IOException("connection reset")
      def keys(prefix: String) = Seq.empty
      def delete(key: String) = ()

    Publisher.artifactsUnder(exploding, PublishTarget(), "org.example") match
      case Left(PublishError.StoreFailure(op, _)) => assertEquals(op, "listing artifacts")
      case other                                  => fail(s"expected StoreFailure, got $other")
  }

  test("a response lost after the store committed is reconciled, not called a conflict") {
    val backing = InMemoryObjectStore()
    var loseResponse = true
    val lossy = new ObjectStore:
      def head(key: String) = backing.head(key)
      def put(key: String, body: Body, headers: ObjectHeaders, precondition: Precondition) =
        Retry(TestRetry.policy(1), TestRetry.anything) {
          val result = backing.put(key, body, headers, precondition)
          if loseResponse then
            loseResponse = false
            throw java.io.IOException("response lost after commit")
          result
        }
      def childPrefixes(prefix: String) = backing.childPrefixes(prefix)
      def keys(prefix: String) = backing.keys(prefix)
      def delete(key: String) = backing.delete(key)

    val ledgerPath = Files.createTempDirectory("ledger").resolve("l.txt")
    val opts = serial.copy(ledger = Ledger.open(ledgerPath, "b/releases"))
    val report = Publisher.publish(lossy, oneVersion(), PublishTarget(), opts)

    assert(report.isRight, report)
    // The object whose response was lost still counts as this run's work, ledger included.
    assertEquals(report.toOption.get.uploaded.size, 6)
    val recorded = Files.readAllLines(ledgerPath).size
    assertEquals(recorded, 7) // six objects plus the scope marker
  }

  private val SidecarKey = "releases/org/example/lib_3/1.0.0/lib_3-1.0.0.jar.sha1"

  /** Same length as a real sha1 sidecar, so a comparison has to look at the content. */
  private val ImpostorSidecar = ("f" * 40) + "\n"

  private def seedImpostor(store: ObjectStore): Unit =
    store.put(SidecarKey, Body.Bytes(ImpostorSidecar.getBytes), ObjectHeaders("text/plain", "x"), Precondition.Unconditional).discard

  test("a refused write holding different content of the same size is a real conflict") {
    val store = InMemoryObjectStore()
    seedImpostor(store)

    Publisher.publish(store, oneVersion(), PublishTarget(), serial) match
      case Left(PublishError.ConcurrentWrite(k)) => assertEquals(k, SidecarKey)
      case other                                 => fail(s"expected ConcurrentWrite, got $other")
  }

  test("a refused write is ambiguous only when nothing at all can be compared") {
    val backing = InMemoryObjectStore()
    seedImpostor(backing)
    val opaque = new ObjectStore:
      def head(key: String) = backing.head(key).map(_.copy(eTag = None))
      def put(key: String, body: Body, headers: ObjectHeaders, precondition: Precondition) =
        backing.put(key, body, headers, precondition)
      def childPrefixes(prefix: String) = backing.childPrefixes(prefix)
      def keys(prefix: String) = backing.keys(prefix)
      def delete(key: String) = backing.delete(key)

    Publisher.publish(opaque, oneVersion(), PublishTarget(), serial) match
      case Left(PublishError.AmbiguousWrite(k, _)) => assertEquals(k, SidecarKey)
      case other                                   => fail(s"expected AmbiguousWrite, got $other")
  }

  /** An SSE-KMS ETag is opaque but still hex, so its shape cannot be taken as a claim about content. A mismatch has to send the publisher
    * to the object itself, not to a conflict.
    */
  test("an opaque but hex-shaped ETag does not decide a conflict on its own") {
    val backing = InMemoryObjectStore()
    var loseResponse = true
    val kms = new ObjectStore:
      def head(key: String) = backing.head(key).map(_.copy(eTag = Some("\"" + ("a" * 32) + "\"")))
      def put(key: String, body: Body, headers: ObjectHeaders, precondition: Precondition) =
        Retry(TestRetry.policy(1), TestRetry.anything) {
          val result = backing.put(key, body, headers, precondition)
          if loseResponse then
            loseResponse = false
            throw java.io.IOException("response lost after commit")
          result
        }
      def childPrefixes(prefix: String) = backing.childPrefixes(prefix)
      def keys(prefix: String) = backing.keys(prefix)
      def delete(key: String) = backing.delete(key)
      override def read(key: String) = backing.read(key)

    val report = Publisher.publish(kms, oneVersion(), PublishTarget(), serial)
    assert(report.isRight, report)
    assertEquals(report.toOption.get.uploaded.size, 6)
  }

  test("an opaque hex ETag over different content is still a conflict") {
    val backing = InMemoryObjectStore()
    seedImpostor(backing)
    val kms = new ObjectStore:
      def head(key: String) = backing.head(key).map(_.copy(eTag = Some("\"" + ("a" * 32) + "\"")))
      def put(key: String, body: Body, headers: ObjectHeaders, precondition: Precondition) =
        backing.put(key, body, headers, precondition)
      def childPrefixes(prefix: String) = backing.childPrefixes(prefix)
      def keys(prefix: String) = backing.keys(prefix)
      def delete(key: String) = backing.delete(key)
      override def read(key: String) = backing.read(key)

    Publisher.publish(kms, oneVersion(), PublishTarget(), serial) match
      case Left(PublishError.ConcurrentWrite(k)) => assertEquals(k, SidecarKey)
      case other                                 => fail(s"expected ConcurrentWrite, got $other")
  }

  /** Server-side encryption with KMS, and directory buckets, both return an ETag that is not the content MD5. Identity has to come from the
    * object itself.
    */
  test("a store whose ETag is not a digest is reconciled by reading the object back") {
    val backing = InMemoryObjectStore()
    var loseResponse = true
    val encrypted = new ObjectStore:
      def head(key: String) = backing.head(key).map(_.copy(eTag = Some("\"not-a-content-md5\"")))
      def put(key: String, body: Body, headers: ObjectHeaders, precondition: Precondition) =
        Retry(TestRetry.policy(1), TestRetry.anything) {
          val result = backing.put(key, body, headers, precondition)
          if loseResponse then
            loseResponse = false
            throw java.io.IOException("response lost after commit")
          result
        }
      def childPrefixes(prefix: String) = backing.childPrefixes(prefix)
      def keys(prefix: String) = backing.keys(prefix)
      def delete(key: String) = backing.delete(key)
      override def read(key: String) = backing.read(key)

    val report = Publisher.publish(encrypted, oneVersion(), PublishTarget(), serial)
    assert(report.isRight, report)
    assertEquals(report.toOption.get.uploaded.size, 6)
  }

  test("reading back different content is still a conflict, not a false success") {
    val backing = InMemoryObjectStore()
    seedImpostor(backing)
    val encrypted = new ObjectStore:
      def head(key: String) = backing.head(key).map(_.copy(eTag = Some("\"not-a-content-md5\"")))
      def put(key: String, body: Body, headers: ObjectHeaders, precondition: Precondition) =
        backing.put(key, body, headers, precondition)
      def childPrefixes(prefix: String) = backing.childPrefixes(prefix)
      def keys(prefix: String) = backing.keys(prefix)
      def delete(key: String) = backing.delete(key)
      override def read(key: String) = backing.read(key)

    Publisher.publish(encrypted, oneVersion(), PublishTarget(), serial) match
      case Left(PublishError.ConcurrentWrite(k)) => assertEquals(k, SidecarKey)
      case other                                 => fail(s"expected ConcurrentWrite, got $other")
  }

  test("republishing after every version is deleted clears the version list") {
    val store = InMemoryObjectStore()
    published(store, "1.0.0")
    store.keys("releases/org/example/lib_3/1.0.0/").foreach(store.delete)

    val result = Publisher.republishMetadata(store, Seq("org/example/lib_3"), PublishTarget())
    assertEquals(result.map(_.size), Right(1))
    val xml = store.stringOf("releases/org/example/lib_3/maven-metadata.xml").get
    assert(!xml.contains("1.0.0"), xml)
    assert(!xml.contains("<latest>"), xml)
  }

  test("an artifact the store has never held gets no empty metadata written for it") {
    val store = InMemoryObjectStore()
    assertEquals(Publisher.republishMetadata(store, Seq("org/example/nothing"), PublishTarget()), Right(Seq.empty))
    assertEquals(store.size, 0)
  }

  test("a dry run answers for the destination, not just the staging tree") {
    val store = InMemoryObjectStore()
    published(store, "1.0.0")

    val dry = serial.copy(dryRun = true)
    Publisher.publish(store, oneVersion("1.0.0"), PublishTarget(), dry) match
      case Left(_: PublishError.AlreadyPublished) => ()
      case other                                  => fail(s"expected AlreadyPublished, got $other")

    val report = Publisher.publish(store, oneVersion("1.0.0"), PublishTarget(), dry.copy(skipExisting = true)).toOption.get
    assertEquals(report.skipped.map(_.version), Seq("1.0.0"))
    assertEquals(report.published, Seq.empty)
    assertEquals(report.uploaded, Seq.empty)
    assertEquals(report.bytes, 0L)
  }

  test("a store failure surfaces as an error rather than an exception") {
    val exploding = new ObjectStore:
      def head(key: String) = throw java.io.IOException("connection reset")
      def put(key: String, body: Body, headers: ObjectHeaders, precondition: Precondition) =
        PutOutcome.Written
      def childPrefixes(prefix: String) = Seq.empty
      def keys(prefix: String) = Seq.empty
      def delete(key: String) = ()

    Publisher.publish(exploding, oneVersion(), PublishTarget(), serial) match
      case Left(PublishError.StoreFailure(op, cause)) =>
        assertEquals(op, "publish")
        assertEquals(cause.getMessage, "connection reset")
      case other => fail(s"expected StoreFailure, got $other")
  }

class ParallelSuite extends munit.FunSuite:

  /** One pool per test, shut down with it; `Parallel` no longer makes its own. */
  private def withPool[A](threads: Int)(f: ExecutorService ?=> A): A =
    val pool = Executors.newFixedThreadPool(threads)
    try f(using pool)
    finally pool.shutdownNow().discard

  test("results keep input order regardless of parallelism") {
    val in = (1 to 200).toVector
    withPool(16)(assertEquals(Parallel.map(in)(i => Right(i * 2)), Right(in.map(_ * 2))))
  }

  test("sequential and parallel agree") {
    val in = (1 to 50).toVector
    assertEquals(withPool(1)(Parallel.map(in)(i => Right(i))), withPool(8)(Parallel.map(in)(i => Right(i))))
  }

  test("the first failure short-circuits") {
    withPool(4)(assertEquals(Parallel.map(Seq(1, 2, 3))(i => if i == 2 then Left("boom") else Right(i)), Left("boom")))
  }

  test("work queued behind a failure is never started") {
    val started = java.util.concurrent.atomic.AtomicInteger(0)
    val items = (1 to 200).toVector
    val result = withPool(2)(Parallel.map(items) { i =>
      started.incrementAndGet().discard
      if i == 1 then Left("boom")
      else
        Thread.sleep(1)
        Right(i)
    })
    assertEquals(result, Left("boom"))
    assert(started.get() < items.size, s"expected an early stop, ran ${started.get()} of ${items.size}")
  }

  test("empty input submits nothing, so a dead pool is never touched") {
    val dead = Executors.newFixedThreadPool(1)
    dead.shutdownNow().discard
    given ExecutorService = dead
    assertEquals(Parallel.map(Seq.empty[Int])(i => Right(i)), Right(Seq.empty))
  }
