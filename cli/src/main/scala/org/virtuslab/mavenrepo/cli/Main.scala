package org.virtuslab.mavenrepo.cli

import caseapp.*
import org.virtuslab.mavenrepo.*
import org.virtuslab.mavenrepo.cloudflare.CloudflarePurger

import java.nio.file.Path
import scala.util.control.NonFatal

@HelpMessage("Publish a staged Maven-layout directory to an S3-compatible object store")
final case class PublishArgs(
    @HelpMessage("Directory holding the Maven-layout tree to publish")
    staging: String = "staging",
    @HelpMessage("Target bucket (required)")
    bucket: String = "",
    @HelpMessage("Top-level key prefix; empty publishes at the bucket root")
    prefix: String = "releases",
    @HelpMessage("S3 endpoint; omit for real AWS S3")
    endpoint: Option[String] = None,
    @HelpMessage("Region; defaults to the AWS chain for S3 and to 'auto' behind --endpoint")
    region: Option[String] = None,
    @HelpMessage("Address the bucket as a path rather than a subdomain; MinIO needs this")
    pathStyle: Boolean = false,
    @HelpMessage("Print the exact key set and byte count, write nothing")
    @Name("n")
    dryRun: Boolean = false,
    @HelpMessage("Treat an already-published version as done instead of an error")
    skipExisting: Boolean = false,
    @HelpMessage("Succeed instead of failing when the staging directory holds no version")
    allowEmpty: Boolean = false,
    @HelpMessage("Publish -SNAPSHOT versions, which this repository makes immutable")
    allowSnapshots: Boolean = false,
    @HelpMessage("Upload staged checksums without checking them against their artifacts")
    trustStagedChecksums: Boolean = false,
    @HelpMessage("Publish a POM without checking that it declares the coordinates it is filed under")
    trustPomCoordinates: Boolean = false,
    @HelpMessage("Ledger file recording uploaded keys, so an interrupted run can resume cheaply")
    ledger: Option[String] = None,
    @HelpMessage("Concurrent uploads; past ~64 object stores throttle and throughput collapses")
    parallelism: Int = 32,
    @HelpMessage("Public base URL, e.g. https://maven.example.com; with --cf-zone-id, enables purging")
    publicUrl: Option[String] = None,
    @HelpMessage("Cloudflare zone id; with --public-url, purges metadata after publishing")
    cfZoneId: Option[String] = None
)

@HelpMessage("Rebuild maven-metadata.xml for a group from what the store holds")
final case class RepublishMetadataArgs(
    @HelpMessage("Target bucket (required)")
    bucket: String = "",
    @HelpMessage("Group id whose artifacts should be rebuilt (required)")
    group: String = "",
    prefix: String = "releases",
    endpoint: Option[String] = None,
    region: Option[String] = None,
    pathStyle: Boolean = false,
    publicUrl: Option[String] = None,
    cfZoneId: Option[String] = None
)

object Main extends CommandsEntryPoint:
  def progName = "cf-maven-repo"
  def commands = Seq(PublishCommand, RepublishMetadataCommand)

/** The same bucket name on R2, on MinIO and on AWS is three different destinations, so the host belongs in the identity of a ledger as much
  * as the bucket and prefix do.
  */
private def ledgerScope(args: PublishArgs): String =
  val host = args.endpoint.orElse(sys.env.get("AWS_ENDPOINT_URL_S3")).getOrElse("s3.amazonaws.com")
  s"$host/${args.bucket}/${args.prefix}"

/** @param purging false for a dry run, which reaches no cache and so needs no purge credential */
private def buildTarget(
    prefix: String,
    publicUrl: Option[String],
    cfZoneId: Option[String],
    purging: Boolean = true
): Either[String, PublishTarget] =
  (publicUrl, cfZoneId) match
    case (None, None)    => Right(PublishTarget(prefix = prefix))
    case (Some(_), None) =>
      Left("--public-url needs --cf-zone-id: without a zone there is nothing to purge")
    case (None, Some(_)) =>
      Left("--cf-zone-id needs --public-url: purge works on URLs, not on object keys")
    case (url @ Some(_), Some(_)) if !purging =>
      Right(PublishTarget(prefix = prefix, publicUrl = url))
    case (url @ Some(_), Some(zone)) =>
      CloudflarePurger.fromEnv(zone).map { purger =>
        PublishTarget(prefix = prefix, purger = Some(purger), publicUrl = url)
      }

private def describe(e: PublishError): String = e match
  case PublishError.InvalidLayout(dir, reason) =>
    s"$dir is not a version directory: $reason"
  case PublishError.AlreadyPublished(c, key) =>
    s"refusing to republish $c: $key already exists. A release version is never rewritten.\n" +
      "  (pass --skip-existing to treat it as already done)"
  case PublishError.MissingPom(directory, expected) =>
    s"$directory holds a .pom but not $expected, so nothing would mark the version published"
  case PublishError.InvalidPom(pom, problem) => s"$pom cannot be published: $problem"
  case PublishError.MissingETag(key)         =>
    s"the store returned no ETag for $key, so its version list cannot be rewritten safely"
  case PublishError.NothingToPublish(root) =>
    s"no version directory under $root - a version directory is one holding a .pom.\n" +
      "  (pass --allow-empty if publishing nothing is expected)"
  case PublishError.SnapshotVersion(c) =>
    s"$c is a snapshot, and everything published here is immutable.\n" +
      "  (pass --allow-snapshots to publish it as an ordinary version anyway)"
  case PublishError.ChecksumMismatch(file, algorithm, expected, actual) =>
    s"$file declares $algorithm $expected but the artifact hashes to $actual"
  case PublishError.ConcurrentWrite(key) =>
    s"$key appeared while we were writing it, holding different content - another run won the race"
  case PublishError.AmbiguousWrite(key, detail) =>
    s"could not establish who wrote $key: $detail"
  case PublishError.MetadataConflict(key) =>
    s"gave up rewriting $key - another publisher kept changing the version list"
  case PublishError.UploadFailed(key, cause) => s"upload of $key failed: ${cause.getMessage}"
  case PublishError.StoreFailure(op, cause)  => s"$op failed: ${cause.getMessage}"
  case PublishError.PurgeFailed(message)     => s"cache purge failed: $message"

object PublishCommand extends Command[PublishArgs]:
  override def name = "publish"

  def run(args: PublishArgs, remaining: RemainingArgs): Unit =
    if args.bucket.isEmpty then fail("--bucket is required")
    val _ = remaining

    val target =
      buildTarget(args.prefix, args.publicUrl, args.cfZoneId, purging = !args.dryRun).fold(fail, identity)
    val options = PublishOptions(
      dryRun = args.dryRun,
      skipExisting = args.skipExisting,
      parallelism = args.parallelism,
      allowEmpty = args.allowEmpty,
      allowSnapshots = args.allowSnapshots,
      verifyStagedChecksums = !args.trustStagedChecksums,
      verifyPomCoordinates = !args.trustPomCoordinates,
      ledger = args.ledger
        .map(l => Ledger.open(Path.of(l), ledgerScope(args)))
        .getOrElse(Ledger.disabled)
    )

    withStore(args.bucket, args.endpoint, args.region, args.pathStyle) { store =>
      Publisher.publish(store, Path.of(args.staging), target, options) match
        case Left(error)   => fail(describe(error))
        case Right(report) =>
          report.published.foreach(c => println(s"  published $c"))
          report.skipped.foreach(c => println(s"  skipped   $c (already published)"))
          report.metadata.foreach(k => println(s"  metadata  $k"))
          if report.purged.nonEmpty then println(s"  purged    ${report.purged.size} URL(s)")
          val verb = if args.dryRun then "would publish" else "published"
          println(s"\n$verb ${report.uploaded.size} object(s), ${report.bytes} bytes")
    }

object RepublishMetadataCommand extends Command[RepublishMetadataArgs]:
  override def name = "republish-metadata"

  def run(args: RepublishMetadataArgs, remaining: RemainingArgs): Unit =
    if args.bucket.isEmpty then fail("--bucket is required")
    if args.group.isEmpty then fail("--group is required")
    val _ = remaining

    val target = buildTarget(args.prefix, args.publicUrl, args.cfZoneId).fold(fail, identity)
    withStore(args.bucket, args.endpoint, args.region, args.pathStyle) { store =>
      Publisher.artifactsUnder(store, target, args.group) match
        case Left(error)                           => fail(describe(error))
        case Right(artifacts) if artifacts.isEmpty => fail(s"no artifacts found under ${args.group}")
        case Right(artifacts)                      =>
          Publisher.republishMetadata(store, artifacts, target) match
            case Left(error) => fail(describe(error))
            case Right(keys) =>
              keys.foreach(k => println(s"  rebuilt $k"))
              println(s"\nrebuilt ${keys.size} metadata file(s)")
    }

/** Building the client reads the environment and can fail on its own - a region that does not parse, an endpoint that is not a URI - which
  * should read like every other error, not like a crash.
  */
private def withStore(
    bucket: String,
    endpoint: Option[String],
    region: Option[String],
    pathStyle: Boolean
)(use: ObjectStore => Unit): Unit =
  val store =
    try S3ObjectStore(bucket, endpoint, region, pathStyle)
    catch case NonFatal(t) => fail(s"could not open $bucket: ${t.getMessage}")
  try use(store)
  finally store.close()

private def fail(message: String): Nothing =
  System.err.println(s"error: $message")
  sys.exit(1)
