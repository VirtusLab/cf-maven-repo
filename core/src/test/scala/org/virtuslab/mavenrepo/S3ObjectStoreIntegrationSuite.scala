package org.virtuslab.mavenrepo

import com.dimafeng.testcontainers.MinIOContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import org.testcontainers.DockerClientFactory
import org.testcontainers.utility.DockerImageName

import java.net.URI
import java.nio.file.Files

/** The S3 contract, against a real implementation rather than the in-memory stand-in.
  *
  * Conditional writes, the error mapping around them and paginated listing are the parts of this store that no amount of unit testing can
  * confirm, because they are assertions about what a server does.
  */
class S3ObjectStoreIntegrationSuite extends munit.FunSuite with TestContainerForAll:

  /** Silo, the community-maintained fork of the MinIO server, because MinIO no longer distributes its own: the Docker Hub repository was
    * deleted in September 2026. Silo keeps the protocol, the `MINIO_*` variables and the health routes, so the MinIO wrapper drives it
    * unchanged. Pinned, because the wrapper's default is a September 2023 build that predates MinIO honouring the `If-None-Match: *`
    * wildcard, and so accepts a second write this store expects to be refused, failing two tests here for a reason that is not this code.
    */
  override val containerDef: MinIOContainer.Def =
    MinIOContainer.Def(DockerImageName.parse("pgsty/silo:RELEASE.2026-09-16T00-00-00Z").asCompatibleSubstituteFor("minio/minio"))

  override def munitIgnore: Boolean = !S3ObjectStoreIntegrationSuite.dockerAvailable

  private val bucket = "cf-maven-test"

  private def credentials(c: MinIOContainer) =
    StaticCredentialsProvider.create(AwsBasicCredentials.create(c.userName, c.password))

  /** MinIO has no bucket until something makes one, and provisioning is not this store's job. */
  private def ensureBucket(c: MinIOContainer): Unit =
    val client = S3Client
      .builder()
      .endpointOverride(URI.create(c.s3URL))
      .credentialsProvider(credentials(c))
      .region(software.amazon.awssdk.regions.Region.US_EAST_1)
      .forcePathStyle(true)
      .build()
    try
      if !client.listBuckets().buckets().stream().anyMatch(_.name() == bucket) then
        client.createBucket(CreateBucketRequest.builder().bucket(bucket).build()).discard
    finally client.close()

  private def withStore(c: MinIOContainer)(f: S3ObjectStore => Unit): Unit =
    ensureBucket(c)
    val store = S3ObjectStore(
      bucket,
      endpoint = Some(c.s3URL),
      region = Some("us-east-1"),
      pathStyle = true,
      credentials = Some(credentials(c))
    )
    try f(store)
    finally store.close()

  private val headers = ObjectHeaders("text/plain", "public, max-age=60")

  private def bytes(s: String) = Body.Bytes(s.getBytes("UTF-8"))

  test("head reports nothing for a missing key and size plus ETag for a present one") {
    withContainers { c =>
      withStore(c) { store =>
        val key = s"${unique()}/probe.txt"
        assertEquals(store.head(key), None)
        assertEquals(store.put(key, bytes("hello"), headers, Precondition.Unconditional), PutOutcome.Written)
        val stored = store.head(key).get
        assertEquals(stored.size, 5L)
        assert(stored.eTag.isDefined, "a real store reports an ETag")
      }
    }
  }

  test("IfAbsent lets the first write through and refuses the second") {
    withContainers { c =>
      withStore(c) { store =>
        val key = s"${unique()}/once.txt"
        assertEquals(store.put(key, bytes("first"), headers, Precondition.IfAbsent), PutOutcome.Written)
        assertEquals(store.put(key, bytes("second"), headers, Precondition.IfAbsent), PutOutcome.PreconditionFailed)
        assertEquals(store.head(key).map(_.size), Some(5L))
      }
    }
  }

  test("IfMatch accepts the current ETag and refuses a stale one") {
    withContainers { c =>
      withStore(c) { store =>
        val key = s"${unique()}/cas.txt"
        store.put(key, bytes("one"), headers, Precondition.Unconditional).discard
        val first = store.head(key).get.eTag.get

        assertEquals(store.put(key, bytes("two"), headers, Precondition.IfMatch(first)), PutOutcome.Written)
        // `first` now describes a version that is no longer there.
        assertEquals(store.put(key, bytes("three"), headers, Precondition.IfMatch(first)), PutOutcome.PreconditionFailed)
        assertEquals(store.read(key).map(readAll), Some("two"))
      }
    }
  }

  test("an IfMatch write against a deleted key is a precondition failure, not an error") {
    withContainers { c =>
      withStore(c) { store =>
        val key = s"${unique()}/vanished.txt"
        store.put(key, bytes("here"), headers, Precondition.Unconditional).discard
        val eTag = store.head(key).get.eTag.get
        store.delete(key)
        assertEquals(store.put(key, bytes("again"), headers, Precondition.IfMatch(eTag)), PutOutcome.PreconditionFailed)
      }
    }
  }

  test("a file body is streamed, and its headers survive the round trip") {
    withContainers { c =>
      withStore(c) { store =>
        val file = Files.createTempFile("artifact", ".jar")
        Files.writeString(file, "x" * 200_000).discard
        val key = s"${unique()}/big.jar"
        val jarHeaders = ObjectHeaders("application/java-archive", CachePolicy.DefaultImmutable)
        assertEquals(store.put(key, Body.FromFile(file), jarHeaders, Precondition.IfAbsent), PutOutcome.Written)
        assertEquals(store.head(key).map(_.size), Some(200_000L))
        assertEquals(store.read(key).map(readAll(_).length), Some(200_000))
      }
    }
  }

  test("listing crosses page boundaries and comes back sorted") {
    withContainers { c =>
      withStore(c) { store =>
        val prefix = unique()
        // MinIO pages at 1000 keys; this run deliberately crosses that.
        val count = 1200
        (1 to count).foreach { i =>
          store.put(f"$prefix/k$i%05d.txt", bytes("x"), headers, Precondition.Unconditional).discard
        }
        val keys = store.keys(s"$prefix/")
        assertEquals(keys.size, count)
        assertEquals(keys, keys.sorted)
        assertEquals(keys.head, f"$prefix/k00001.txt")
      }
    }
  }

  test("child prefixes are the immediate directory names, not the keys under them") {
    withContainers { c =>
      withStore(c) { store =>
        val prefix = unique()
        Seq("1.0.0/a.pom", "1.0.0/a.jar", "2.0.0/a.pom", "10.0.0/a.pom").foreach { rest =>
          store.put(s"$prefix/$rest", bytes("x"), headers, Precondition.Unconditional).discard
        }
        assertEquals(store.childPrefixes(prefix), Seq("1.0.0", "10.0.0", "2.0.0"))
      }
    }
  }

  test("delete removes the object") {
    withContainers { c =>
      withStore(c) { store =>
        val key = s"${unique()}/gone.txt"
        store.put(key, bytes("x"), headers, Precondition.Unconditional).discard
        store.delete(key)
        assertEquals(store.head(key), None)
        assertEquals(store.read(key), None)
      }
    }
  }

  test("a full publish round trip lands every file and consistent metadata") {
    withContainers { c =>
      withStore(c) { store =>
        val prefix = unique()
        val root = Files.createTempDirectory("staging")
        val dir = root.resolve("org/example/lib_3/1.0.0")
        Files.createDirectories(dir)
        Files
          .writeString(
            dir.resolve("lib_3-1.0.0.pom"),
            "<project><groupId>org.example</groupId><artifactId>lib_3</artifactId>" +
              "<version>1.0.0</version></project>"
          )
          .discard
        Files.writeString(dir.resolve("lib_3-1.0.0.jar"), "jar bytes").discard

        val target = PublishTarget(prefix = prefix)
        val report = Publisher.publish(store, root, target, PublishOptions(parallelism = 4))
        assert(report.isRight, report)

        val base = s"$prefix/org/example/lib_3"
        val keys = store.keys(s"$base/").toSet
        Seq(
          "1.0.0/lib_3-1.0.0.pom",
          "1.0.0/lib_3-1.0.0.jar",
          "1.0.0/lib_3-1.0.0.jar.sha1",
          "1.0.0/lib_3-1.0.0.jar.md5",
          "maven-metadata.xml"
        )
          .foreach(k => assert(keys.contains(s"$base/$k"), s"missing $base/$k"))

        val xml = store.read(s"$base/maven-metadata.xml").map(readAllBytes).get
        assert(String(xml, "UTF-8").contains("<version>1.0.0</version>"))
        Checksums.ForMetadata.foreach { a =>
          val sidecar = store.read(s"$base/maven-metadata.xml${a.suffix}").map(readAll).get
          assertEquals(sidecar, String(Checksums.content(a, xml), "UTF-8"), s"${a.suffix} does not describe the metadata beside it")
        }

        // A second run must refuse the version rather than rewrite it.
        Publisher.publish(store, root, target, PublishOptions(parallelism = 4)) match
          case Left(_: PublishError.AlreadyPublished) => ()
          case other                                  => fail(s"expected AlreadyPublished, got $other")
      }
    }
  }

  private def unique() = s"it-${java.util.UUID.randomUUID().toString.take(8)}"

  private def readAllBytes(in: java.io.InputStream): Array[Byte] =
    try in.readAllBytes()
    finally in.close()

  private def readAll(in: java.io.InputStream): String = String(readAllBytes(in), "UTF-8")

object S3ObjectStoreIntegrationSuite:
  /** Checked once: the probe itself takes a moment, and a machine without Docker skips the whole suite rather than failing it.
    * `CF_MAVEN_NO_DOCKER` skips it deliberately.
    */
  lazy val dockerAvailable: Boolean =
    sys.env.get("CF_MAVEN_NO_DOCKER").isEmpty &&
      (try DockerClientFactory.instance().isDockerAvailable
      catch case _: Throwable => false)
