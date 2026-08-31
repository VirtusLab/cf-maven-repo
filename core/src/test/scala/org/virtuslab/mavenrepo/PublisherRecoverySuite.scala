package org.virtuslab.mavenrepo

import org.virtuslab.mavenrepo.FaultInjectingObjectStore.Operation

import java.nio.file.{Files, Path}

/** What a publish leaves behind when it is cut off part way, and whether a later run can finish the job.
  *
  * The invariant under test throughout is that a *reported* success means a resolvable one: every version the report names has its POM, its
  * artifacts and their checksums in the store, and the metadata lists it. Anything less is a repository that advertises what it does not
  * hold.
  */
class PublisherRecoverySuite extends munit.FunSuite:

  private val serial = PublishOptions(parallelism = 1)

  private def pomFor(version: String) =
    s"<project><groupId>org.example</groupId><artifactId>lib_3</artifactId>" +
      s"<version>$version</version></project>"

  private def staging(version: String = "1.0.0"): Path =
    val root = Files.createTempDirectory("recovery")
    val dir = root.resolve(s"org/example/lib_3/$version")
    Files.createDirectories(dir)
    Files.writeString(dir.resolve(s"lib_3-$version.pom"), pomFor(version)).discard
    Files.writeString(dir.resolve(s"lib_3-$version.jar"), "jar bytes").discard
    root

  private def key(version: String, name: String) =
    s"releases/org/example/lib_3/$version/lib_3-$version$name"

  private val MetadataKey = "releases/org/example/lib_3/maven-metadata.xml"

  /** The whole point of the ordering rules: a report that names a version must mean a resolver can fetch it.
    */
  private def assertResolvable(store: InMemoryObjectStore, report: PublishReport): Unit =
    report.published.foreach { c =>
      val base = s"releases/${c.versionPath}/${c.artifactId}-${c.version}"
      Seq(".pom", ".jar", ".jar.sha1", ".jar.md5", ".pom.sha1", ".pom.md5").foreach { suffix =>
        assert(store.head(base + suffix).isDefined, s"$base$suffix is missing")
      }
      val xml = store.stringOf(s"releases/${c.artifactPath}/maven-metadata.xml")
      assert(xml.exists(_.contains(s"<version>${c.version}</version>")), s"metadata does not list ${c.version}")
    }

  test("an interruption in the checksum phase leaves nothing a resolver can reach") {
    val backing = InMemoryObjectStore()
    val store = FaultInjectingObjectStore(backing).failOn(
      Operation.Put,
      matches = _.endsWith(".jar.sha1")
    )
    val result = Publisher.publish(store, staging(), PublishTarget(), serial)

    assert(result.isLeft, result)
    assertEquals(backing.head(key("1.0.0", ".pom")), None, "the marker must not exist")
    assertEquals(backing.head(MetadataKey), None, "nothing may advertise the version")
  }

  test("an interruption before the pom leaves the version unmarked and unlisted") {
    val backing = InMemoryObjectStore()
    val store = FaultInjectingObjectStore(backing).failOn(Operation.Put, matches = _.endsWith(".pom"))
    val result = Publisher.publish(store, staging(), PublishTarget(), serial)

    assert(result.isLeft, result)
    assert(backing.head(key("1.0.0", ".jar")).isDefined, "artifacts land before the marker")
    assertEquals(backing.head(key("1.0.0", ".pom")), None)
    assertEquals(backing.head(MetadataKey), None)
  }

  test("an interruption after the pom leaves an unlisted version that a rerun completes") {
    val backing = InMemoryObjectStore()
    val store = FaultInjectingObjectStore(backing)
      .failOn(Operation.Put, matches = _.endsWith("maven-metadata.xml"))
    assert(Publisher.publish(store, staging(), PublishTarget(), serial).isLeft)

    assert(backing.head(key("1.0.0", ".pom")).isDefined, "the version is complete but unlisted")
    assertEquals(backing.head(MetadataKey), None)

    // The repair path: the tree is still there, so the same command finishes the job.
    val report = Publisher.publish(backing, staging(), PublishTarget(), serial.copy(skipExisting = true))
    assert(report.isRight, report)
    assert(backing.stringOf(MetadataKey).exists(_.contains("<version>1.0.0</version>")))
  }

  test("a metadata checksum that fails to write is reported, not passed over") {
    Checksums.ForMetadata.foreach { algorithm =>
      val backing = InMemoryObjectStore()
      val store = FaultInjectingObjectStore(backing)
        .failOn(Operation.Put, matches = _.endsWith(s"maven-metadata.xml${algorithm.suffix}"))
      Publisher.publish(store, staging(), PublishTarget(), serial) match
        case Left(PublishError.UploadFailed(k, _)) => assert(k.endsWith(algorithm.suffix), k)
        case other                                 => fail(s"expected UploadFailed for ${algorithm.suffix}, got $other")
    }
  }

  test("a transient write failure is retried and the publish completes") {
    val backing = InMemoryObjectStore()
    val store = FaultInjectingObjectStore(backing)
      .failOn(Operation.Put, matches = _.endsWith(".jar"), times = 1)
    store.retryable = true
    store.maxRetries = 2

    val report = Publisher.publish(store, staging(), PublishTarget(), serial)
    assert(report.isRight, report)
    assertResolvable(backing, report.toOption.get)
  }

  test("a write that keeps failing gives up after the retry limit") {
    val backing = InMemoryObjectStore()
    val store = FaultInjectingObjectStore(backing).failOn(Operation.Put, matches = _.endsWith(".jar"))
    store.retryable = true
    store.maxRetries = 2

    Publisher.publish(store, staging(), PublishTarget(), serial) match
      case Left(PublishError.UploadFailed(k, _)) => assert(k.endsWith(".jar"), k)
      case other                                 => fail(s"expected UploadFailed, got $other")
    // The first attempt plus two retries, and nothing beyond.
    assertEquals(store.callsTo(Operation.Put, key("1.0.0", ".jar")), 3)
  }

  test("a write the store calls permanent is not retried at all") {
    val backing = InMemoryObjectStore()
    val store = FaultInjectingObjectStore(backing).failOn(Operation.Put, matches = _.endsWith(".jar"))
    store.retryable = false

    assert(Publisher.publish(store, staging(), PublishTarget(), serial).isLeft)
    assertEquals(store.callsTo(Operation.Put, key("1.0.0", ".jar")), 1)
  }

  test("a lost response is reconciled, and the run still reports a resolvable version") {
    val backing = InMemoryObjectStore()
    val store = FaultInjectingObjectStore(backing)
      .failOn(Operation.Put, matches = _.endsWith(".jar"), times = 1, commitFirst = true)
    store.retryable = true
    store.maxRetries = 2

    val report = Publisher.publish(store, staging(), PublishTarget(), serial)
    assert(report.isRight, report)
    assertResolvable(backing, report.toOption.get)
  }

  test("a resumed run finishes a repository that an interruption left incomplete") {
    val backing = InMemoryObjectStore()
    val ledger = Files.createTempDirectory("ledger").resolve("l.txt")
    val root = staging()

    val interrupted = FaultInjectingObjectStore(backing).failOn(Operation.Put, matches = _.endsWith(".pom"))
    assert(
      Publisher
        .publish(interrupted, root, PublishTarget(), serial.copy(ledger = Ledger.open(ledger, "h/b/releases")))
        .isLeft
    )

    val report = Publisher.publish(backing, root, PublishTarget(), serial.copy(ledger = Ledger.open(ledger, "h/b/releases")))
    assert(report.isRight, report)
    assertResolvable(backing, report.toOption.get)
  }

  test("a listing failure during metadata does not report a success") {
    val backing = InMemoryObjectStore()
    val store = FaultInjectingObjectStore(backing).failOn(Operation.Keys)
    Publisher.publish(store, staging(), PublishTarget(), serial) match
      case Left(PublishError.StoreFailure(op, _)) => assertEquals(op, "publish")
      case other                                  => fail(s"expected StoreFailure, got $other")
  }

  test("two publishers adding different versions both survive in the metadata") {
    val backing = InMemoryObjectStore()
    assert(Publisher.publish(backing, staging("1.0.0"), PublishTarget(), serial).isRight)

    // The second publisher's listing runs before the first's version is written, so its first
    // conditional write is against state that no longer exists by the time it lands.
    val racing = FaultInjectingObjectStore(backing)
    val second = Publisher.publish(racing, staging("2.0.0"), PublishTarget(), serial)
    assert(second.isRight, second)

    val xml = backing.stringOf(MetadataKey).get
    assert(xml.contains("<version>1.0.0</version>"), xml)
    assert(xml.contains("<version>2.0.0</version>"), xml)
    val bytes = backing.bytesOf(MetadataKey).get
    Checksums.ForMetadata.foreach { a =>
      assertEquals(backing.stringOf(MetadataKey + a.suffix), Some(String(Checksums.content(a, bytes), "UTF-8")))
    }
  }
