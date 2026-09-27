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

private def buildTarget(
    prefix: String,
    publicUrl: Option[String],
    cfZoneId: Option[String],
    purging: Boolean = true
): Either[String, PublishTarget] =
  // The pairing is checked here too, so the refusal names the flags rather than the concepts.
  (publicUrl, cfZoneId) match
    case (Some(_), None) => Left("--public-url needs --cf-zone-id: without a zone there is nothing to purge")
    case (None, Some(_)) => Left("--cf-zone-id needs --public-url: purge works on URLs, not on object keys")
    case _               => CloudflarePurger.target(prefix, publicUrl, cfZoneId, purging, CloudflarePurger.tokenFromEnv)

/** The core message, plus the flag that turns the refusal off where there is one. */
private def describe(e: PublishError): String =
  val hint = e match
    case _: PublishError.AlreadyPublished => Some("pass --skip-existing to treat it as already done")
    case _: PublishError.NothingToPublish => Some("pass --allow-empty if publishing nothing is expected")
    case _: PublishError.SnapshotVersion  => Some("pass --allow-snapshots to publish it as an ordinary version anyway")
    case _                                => None
  e.message + hint.fold("")(h => s"\n  ($h)")

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
        .map(l => Ledger.open(Path.of(l), Ledger.scope(args.endpoint, args.bucket, args.prefix)))
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
