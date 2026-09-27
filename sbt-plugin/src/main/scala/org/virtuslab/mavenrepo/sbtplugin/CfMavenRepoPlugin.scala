package org.virtuslab.mavenrepo.sbtplugin

import org.virtuslab.mavenrepo.*
import org.virtuslab.mavenrepo.cloudflare.CloudflarePurger
import sbt.*
import sbt.Keys.*
import sbt.ProjectExtra.extract
import sbt.internal.SessionSettings
import software.amazon.awssdk.auth.credentials.{AwsCredentialsProvider, ProfileCredentialsProvider}

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.util.control.NonFatal

/** Stages every module of a build into one directory and publishes it to an S3-compatible store in a single run, so each artifact's
  * `maven-metadata.xml` is rebuilt once however many modules there are.
  *
  * Every key is build-wide: set it with `ThisBuild /`.
  */
object CfMavenRepoPlugin extends AutoPlugin:
  override def trigger = allRequirements
  override def requires = plugins.JvmPlugin

  object autoImport:
    val cfMavenRepoBucket = settingKey[String]("Target bucket (required)")
    val cfMavenRepoEndpoint = settingKey[Option[String]]("S3 endpoint; None for real AWS S3")
    val cfMavenRepoRegion = settingKey[Option[String]]("Region; the AWS chain for S3 and 'auto' behind an endpoint when None")
    val cfMavenRepoPathStyle = settingKey[Boolean]("Address the bucket as a path rather than a subdomain; MinIO needs this")
    val cfMavenRepoPrefix = settingKey[String]("Top-level key prefix; empty publishes at the bucket root")
    val cfMavenRepoPublicUrl = settingKey[Option[String]]("Public base URL; with cfMavenRepoCfZoneId, enables purging")
    val cfMavenRepoCfZoneId = settingKey[Option[String]]("Cloudflare zone id; with cfMavenRepoPublicUrl, purges metadata after publishing")
    val cfMavenRepoDryRun = settingKey[Boolean]("Report the exact key set and byte count, write nothing")
    val cfMavenRepoSkipExisting = settingKey[Boolean]("Treat an already-published version as done instead of an error")
    val cfMavenRepoAllowEmpty = settingKey[Boolean]("Succeed instead of failing when nothing was staged")
    val cfMavenRepoAllowSnapshots = settingKey[Boolean]("Publish -SNAPSHOT versions, which this repository makes immutable")
    val cfMavenRepoTrustStagedChecksums = settingKey[Boolean]("Upload staged checksums without checking them against their artifacts")
    val cfMavenRepoTrustPomCoordinates =
      settingKey[Boolean]("Publish a POM without checking that it declares the coordinates it is filed under")
    val cfMavenRepoParallelism = settingKey[Int]("Concurrent uploads; past ~64 object stores throttle and throughput collapses")
    val cfMavenRepoLedger = settingKey[Option[File]]("Ledger file recording uploaded keys, so an interrupted run can resume cheaply")
    val cfMavenRepoAwsProfile = settingKey[Option[String]]("AWS profile to take credentials from instead of the default chain")
    val cfMavenRepoCredentials =
      settingKey[Option[AwsCredentialsProvider]]("AWS credentials; overrides cfMavenRepoAwsProfile and the default chain")
    val cfMavenRepoCloudflareTokenFile = settingKey[Option[File]]("File holding the Cloudflare API token; otherwise CLOUDFLARE_API_TOKEN")
    val cfMavenRepoStagingDirectory = settingKey[File]("Directory every module is staged into before the upload")
    val cfMavenRepoStaging = settingKey[Option[Resolver]]("Resolver staging into cfMavenRepoStagingDirectory, for publishTo")

  import autoImport.*

  override lazy val buildSettings: Seq[Setting[?]] = Seq(
    cfMavenRepoEndpoint := None,
    cfMavenRepoRegion := None,
    cfMavenRepoPathStyle := false,
    cfMavenRepoPrefix := "releases",
    cfMavenRepoPublicUrl := None,
    cfMavenRepoCfZoneId := None,
    cfMavenRepoDryRun := false,
    cfMavenRepoSkipExisting := false,
    cfMavenRepoAllowEmpty := false,
    cfMavenRepoAllowSnapshots := false,
    cfMavenRepoTrustStagedChecksums := false,
    cfMavenRepoTrustPomCoordinates := false,
    cfMavenRepoParallelism := 32,
    cfMavenRepoLedger := None,
    cfMavenRepoAwsProfile := None,
    cfMavenRepoCredentials := None,
    cfMavenRepoCloudflareTokenFile := None,
    cfMavenRepoStagingDirectory := (ThisBuild / baseDirectory).value / "target" / "cf-maven-staging",
    // MavenCache rather than Resolver.file: it writes the Maven layout with checksums, which is what
    // the upload verifies against.
    cfMavenRepoStaging := Some(MavenCache("cf-maven-staging", cfMavenRepoStagingDirectory.value))
  )

  // Commands rather than tasks, as sbt's own sonaUpload is: they run once per build rather than
  // once per aggregated project, and no task cache can decide they have already run.
  override lazy val globalSettings: Seq[Setting[?]] = Seq(
    commands ++= Seq(releaseCommand, uploadCommand, republishMetadataCommand, restoreCommand, restoreAfterFailureCommand)
  )

  private val ReleaseName = "cfMavenRepoRelease"
  private val UploadName = "cfMavenRepoUpload"
  private val RepublishName = "cfMavenRepoRepublishMetadata"
  private val RestoreName = "cfMavenRepoRestore"
  private val RestoreAfterFailureName = "cfMavenRepoRestoreAfterFailure"

  /** What a release replaces for the length of the run, and puts back afterwards. */
  private final case class Saved(session: SessionSettings, structure: sbt.internal.BuildStructure, onFailure: Option[Exec])

  private val savedKey = AttributeKey[Saved]("cfMavenRepoSaved")

  /** Points every project's `publishTo` at the staging resolver for this run only, then publishes, uploads, and restores the build as it
    * was, whether the run succeeds or fails. The publish task is only ever a string, so `cfMavenRepoRelease publishSigned` signs without
    * this plugin depending on sbt-pgp.
    */
  private lazy val releaseCommand = Command.args(ReleaseName, "<publish command>") { (s, args) =>
    val x = Project.extract(s)
    val staging = x.get(cfMavenRepoStaging)
    IO.delete(x.get(cfMavenRepoStagingDirectory))

    val session = x.session
    val overrides = x.structure.allProjectRefs.map { ref =>
      Scope(Select(ref), Zero, Zero, Zero) / publishTo := staging
    }
    // Raw session settings rather than a plain reapply: cross-building (`+publish`) reapplies the
    // session, and anything outside it would be lost on the first version switch.
    val staged = BuiltinCommands
      .reapply(session.appendRaw(overrides), x.structure, s)
      .put(savedKey, Saved(session, x.structure, s.onFailure))

    val publishCommand = if args.isEmpty then "publish" else args.mkString(" ")
    (publishCommand :: UploadName :: RestoreName :: staged).copy(onFailure = Some(Exec(RestoreAfterFailureName, None)))
  }

  private lazy val restoreCommand = Command.command(RestoreName)(restore(_))

  /** sbt clears `onFailure` before running it, so after restoring this fails again, now under the handler the build had before the release
    * began: an interactive shell goes back to its prompt, a batch run exits non-zero.
    */
  private lazy val restoreAfterFailureCommand = Command.command(RestoreAfterFailureName)(s => restore(s).fail)

  private def restore(s: State): State =
    s.get(savedKey) match
      case None        => s
      case Some(saved) =>
        Project.setProject(saved.session, saved.structure, s.remove(savedKey)).copy(onFailure = saved.onFailure)

  private lazy val uploadCommand = Command.command(UploadName) { s =>
    val x = Project.extract(s)
    val log = s.log
    val dryRun = x.get(cfMavenRepoDryRun)
    val dir = x.get(cfMavenRepoStagingDirectory).toPath

    val outcome = for
      bucket <- bucketOf(x)
      target <- targetOf(x, purging = !dryRun)
      options = PublishOptions(
        dryRun = dryRun,
        skipExisting = x.get(cfMavenRepoSkipExisting),
        parallelism = x.get(cfMavenRepoParallelism),
        allowEmpty = x.get(cfMavenRepoAllowEmpty),
        allowSnapshots = x.get(cfMavenRepoAllowSnapshots),
        verifyStagedChecksums = !x.get(cfMavenRepoTrustStagedChecksums),
        verifyPomCoordinates = !x.get(cfMavenRepoTrustPomCoordinates),
        ledger = x
          .get(cfMavenRepoLedger)
          .map(l => Ledger.open(l.toPath, Ledger.scope(x.get(cfMavenRepoEndpoint), bucket, x.get(cfMavenRepoPrefix))))
          .getOrElse(Ledger.disabled)
      )
      report <- withStore(x, bucket)(Publisher.publish(_, dir, target, options).left.map(describe))
    yield report

    outcome match
      case Left(message) =>
        log.error(message)
        s.fail
      case Right(report) =>
        report.published.foreach(c => log.info(s"  published $c"))
        report.skipped.foreach(c => log.info(s"  skipped   $c (already published)"))
        report.metadata.foreach(k => log.info(s"  metadata  $k"))
        if report.purged.nonEmpty then log.info(s"  purged    ${report.purged.size} URL(s)")
        val verb = if dryRun then "would publish" else "published"
        log.info(s"$verb ${report.uploaded.size} object(s), ${report.bytes} bytes")
        s
  }

  private lazy val republishMetadataCommand = Command.single(RepublishName) { (s, group) =>
    val x = Project.extract(s)
    val log = s.log
    val outcome = for
      bucket <- bucketOf(x)
      target <- targetOf(x, purging = true)
      keys <- withStore(x, bucket) { store =>
        Publisher.artifactsUnder(store, target, group).left.map(describe).flatMap { artifacts =>
          if artifacts.isEmpty then Left(s"no artifacts found under $group")
          else Publisher.republishMetadata(store, artifacts, target).left.map(describe)
        }
      }
    yield keys

    outcome match
      case Left(message) =>
        log.error(message)
        s.fail
      case Right(keys) =>
        keys.foreach(k => log.info(s"  rebuilt $k"))
        log.info(s"rebuilt ${keys.size} metadata file(s)")
        s
  }

  private def bucketOf(x: Extracted): Either[String, String] =
    x.getOpt(cfMavenRepoBucket).filter(_.nonEmpty).toRight("cfMavenRepoBucket is not set: add ThisBuild / cfMavenRepoBucket := \"...\"")

  /** Everything here is read when the command runs, never when the build loads, so a build that never publishes needs no credentials. */
  private def targetOf(x: Extracted, purging: Boolean): Either[String, PublishTarget] =
    (x.get(cfMavenRepoPublicUrl), x.get(cfMavenRepoCfZoneId)) match
      case (Some(_), None) => Left("cfMavenRepoPublicUrl needs cfMavenRepoCfZoneId: without a zone there is nothing to purge")
      case (None, Some(_)) => Left("cfMavenRepoCfZoneId needs cfMavenRepoPublicUrl: purge works on URLs, not on object keys")
      case (url, zone)     =>
        CloudflarePurger.target(x.get(cfMavenRepoPrefix), url, zone, purging, cloudflareToken(x.get(cfMavenRepoCloudflareTokenFile)))

  private def cloudflareToken(file: Option[File]): Either[String, String] = file match
    case None       => CloudflarePurger.tokenFromEnv
    case Some(file) =>
      try Right(Files.readString(file.toPath, StandardCharsets.UTF_8).trim).filterOrElse(_.nonEmpty, s"$file is empty")
      catch case NonFatal(t) => Left(s"could not read the Cloudflare token from $file: ${t.getMessage}")

  /** Building the client reads the environment and can fail on its own - a region that does not parse, a profile that does not exist -
    * which should read like every other error rather than like a crash.
    */
  private def withStore[A](x: Extracted, bucket: String)(use: ObjectStore => Either[String, A]): Either[String, A] =
    quietSlf4j
    val credentials = x.get(cfMavenRepoCredentials).orElse(x.get(cfMavenRepoAwsProfile).map(ProfileCredentialsProvider.create))
    val store =
      try Right(S3ObjectStore(bucket, x.get(cfMavenRepoEndpoint), x.get(cfMavenRepoRegion), x.get(cfMavenRepoPathStyle), credentials))
      catch case NonFatal(t) => Left(s"could not open $bucket: ${t.getMessage}")
    store.flatMap { store =>
      try use(store)
      finally store.close()
    }

  /** The core message, plus the setting that turns the refusal off where there is one. */
  private def describe(e: PublishError): String =
    val hint = e match
      case _: PublishError.AlreadyPublished => Some("set ThisBuild / cfMavenRepoSkipExisting := true to treat it as already done")
      case _: PublishError.NothingToPublish => Some("set ThisBuild / cfMavenRepoAllowEmpty := true if publishing nothing is expected")
      case _: PublishError.SnapshotVersion  =>
        Some("set ThisBuild / cfMavenRepoAllowSnapshots := true to publish it as an ordinary version anyway")
      case _ => None
    e.message + hint.fold("")(h => s"\n  ($h)")

  /** sbt carries slf4j-api 1.7 with no backend and loads it where no plugin's backend is visible, so the first logger the AWS SDK asks for
    * prints a three-line warning. Initialisation happens once per JVM, so it is done here with stderr discarded.
    */
  private lazy val quietSlf4j: Unit =
    val err = System.err
    System.setErr(java.io.PrintStream(java.io.OutputStream.nullOutputStream()))
    try org.slf4j.LoggerFactory.getILoggerFactory().discard
    finally System.setErr(err)
