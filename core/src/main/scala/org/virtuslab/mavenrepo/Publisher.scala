package org.virtuslab.mavenrepo

import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.concurrent.{ExecutorService, Executors, TimeUnit}
import scala.annotation.tailrec
import scala.util.boundary
import scala.util.boundary.break
import scala.util.control.NonFatal

final case class PublishTarget(
    /** Top-level key prefix. Empty publishes at the store root. */
    prefix: String = "releases",
    policy: CachePolicy = CachePolicy(),
    /** Absent when nothing fronts the store, which is the common case. A run with no purger reports no purged URLs, rather than reporting
      * URLs that nothing was asked to invalidate.
      */
    purger: Option[CachePurger] = None,
    /** Public base URL; required to build purge URLs. */
    publicUrl: Option[String] = None
):
  def key(path: String): String = if prefix.isEmpty then path else s"${prefix.stripSuffix("/")}/$path"

final case class PublishOptions(
    dryRun: Boolean = false,
    skipExisting: Boolean = false,
    parallelism: Int = 32,
    /** Publishing nothing is a bad staging path far more often than it is an intent. */
    allowEmpty: Boolean = false,
    /** A snapshot published here would be immutable, which is not what `-SNAPSHOT` means. */
    allowSnapshots: Boolean = false,
    verifyStagedChecksums: Boolean = true,
    verifyPomCoordinates: Boolean = true,
    ledger: Ledger = Ledger.disabled
)

/** Uploads are grouped into phases that run one after another, each internally parallel. The order is what makes a partial run safe to
  * observe: see [[Publisher]].
  */
enum UploadPhase:
  case Checksums, Artifacts, Marker

final case class PlannedUpload(
    key: String,
    body: Body,
    headers: ObjectHeaders,
    precondition: Precondition,
    size: Long,
    phase: UploadPhase
)

final case class MetadataWrite(key: String, sidecars: Seq[String]):
  def keys: Seq[String] = key +: sidecars

final case class PublishReport(
    published: Seq[Coordinates],
    skipped: Seq[Coordinates],
    uploaded: Seq[String],
    bytes: Long,
    metadata: Seq[String],
    purged: Seq[String]
)

enum PublishError:
  case AlreadyPublished(coordinates: Coordinates, key: String)

  /** The staging tree held no version directory at all. */
  case NothingToPublish(root: Path)

  /** A directory in the staging tree holds a POM but does not sit at a coordinate-shaped path. */
  case InvalidLayout(directory: Path, reason: String)

  /** A staged version directory does not hold the POM its own path implies. */
  case MissingPom(directory: Path, expected: String)

  /** The POM is unreadable, or claims to be something other than where it is being published. */
  case InvalidPom(pom: Path, problem: String)

  /** The store returned no ETag for existing metadata, so the version list cannot be rewritten without risking a lost update.
    */
  case MissingETag(key: String)

  case SnapshotVersion(coordinates: Coordinates)

  /** A staged checksum did not describe the artifact beside it. Publishing it would put a permanently wrong sidecar next to an immutable
    * artifact.
    */
  case ChecksumMismatch(file: Path, algorithm: String, expected: String, actual: String)

  /** The key was created between the existence check and the write, holding different content. */
  case ConcurrentWrite(key: String)

  /** A write was refused and what the store holds cannot be shown to be what this run intended. */
  case AmbiguousWrite(key: String, detail: String)

  /** Another publisher kept changing the version list while this one tried to rewrite it. */
  case MetadataConflict(key: String)

  case UploadFailed(key: String, cause: Throwable)

  /** The store failed outside a single object write - a listing, a HEAD, a staging file read. */
  case StoreFailure(operation: String, cause: Throwable)

  case PurgeFailed(message: String)

/** Publishes a staging directory to an object store.
  *
  * Upload order is a correctness property, not a preference. Within a run: every checksum, then every artifact except the POM, then the
  * POMs, then `maven-metadata.xml`.
  *
  *   - A checksum precedes the file it describes, because a resolver asks for the artifact first and only then for its sidecar; a resolver
  *     in strict mode fails on an artifact whose checksum has not landed yet, but can never observe the reverse.
  *   - The POM is written last within a version, so its presence really does mean the version is complete. Both the already-published check
  *     here and any resolver treat it that way.
  *   - `maven-metadata.xml` is the commit point for the repository as a whole: range resolution will not reach a version until metadata
  *     lists it, so an interrupted run leaves unreferenced objects rather than a repository advertising artifacts that are not there.
  */
object Publisher:

  /** Bounds the metadata read-modify-write retry loop; a publisher that loses this many races in a row is contending with something that is
    * not going to stop.
    */
  private val MaxMetadataAttempts = 8

  def publish(
      store: ObjectStore,
      stagingRoot: Path,
      target: PublishTarget = PublishTarget(),
      options: PublishOptions = PublishOptions()
  ): Either[PublishError, PublishReport] =
    guarding("publish")(publishUnguarded(store, stagingRoot, target, options))

  /** The validation prelude reads as a series of independent refusals, so it is written as one: `boundary` marks where a refusal lands and
    * each check leaves by `break`. `return` would do the same here and only here - these are direct returns from this method - but it stops
    * working the moment a check moves inside a lambda, and it says nothing about where control goes.
    */
  private def publishUnguarded(
      store: ObjectStore,
      stagingRoot: Path,
      target: PublishTarget,
      options: PublishOptions
  ): Either[PublishError, PublishReport] = boundary:

    val versions = StagingLayout.discover(stagingRoot) match
      case Right(found) => found
      case Left(bad)    => break(Left(PublishError.InvalidLayout(bad.directory, bad.reason)))

    if versions.isEmpty && !options.allowEmpty then break(Left(PublishError.NothingToPublish(stagingRoot)))

    // Discovery accepts any directory holding a .pom, but only the coordinate-derived name is
    // the completion marker a resolver and this publisher both look for. A version staged under
    // any other POM name would upload cleanly and then be invisible to metadata.
    versions.find(v => !v.files.exists(_.getFileName.toString == v.coordinates.fileName(".pom"))) match
      case Some(v) => break(Left(PublishError.MissingPom(v.directory, v.coordinates.fileName(".pom"))))
      case None    => ()

    if !options.allowSnapshots then
      versions.find(v => MavenVersion.isSnapshot(v.coordinates.version)) match
        case Some(v) => break(Left(PublishError.SnapshotVersion(v.coordinates)))
        case None    => ()

    // Checked up front so the error names the offending version, rather than surfacing as a
    // failed conditional write from inside the pool. A dry run makes the same read-only checks:
    // reporting what a real run would do means asking the destination the same questions.
    val existing = versions.filter(v => store.head(target.key(v.coordinates.pomPath)).isDefined)
    if existing.nonEmpty && !options.skipExisting then
      val v = existing.head
      break(Left(PublishError.AlreadyPublished(v.coordinates, target.key(v.coordinates.pomPath))))
    val (toPublish, skipped) = (versions.filterNot(existing.contains), existing)

    if options.verifyPomCoordinates then
      toPublish.iterator.map(verifyCoordinates).collectFirst { case Some(e) => e } match
        case Some(e) => break(Left(e))
        case None    => ()

    val planned = toPublish.map(planVersion(_, target, options))
    planned.collectFirst { case Left(e) => e } match
      case Some(e) => break(Left(e))
      case None    => ()
    val uploads = planned.collect { case Right(u) => u }.flatten

    if options.dryRun then
      Right(
        PublishReport(
          published = toPublish.map(_.coordinates),
          skipped = skipped.map(_.coordinates),
          uploaded = ordered(uploads).map(_.key),
          bytes = uploads.map(_.size).sum,
          metadata = metadataArtifacts(versions).map(a => target.key(s"$a/${Coordinates.MetadataFileName}")),
          purged = Seq.empty
        )
      )
    else
      for
        written <- execute(store, uploads, options)
        // Includes artifacts skipped as already-published, whose metadata a previous run may have
        // died before writing.
        meta <- publishMetadata(store, metadataArtifacts(versions), target)
        purged <- purge(target, meta)
      yield PublishReport(
        published = toPublish.map(_.coordinates),
        skipped = skipped.map(_.coordinates),
        uploaded = written,
        bytes = uploads.filter(u => written.contains(u.key)).map(_.size).sum,
        metadata = meta.map(_.key),
        purged = purged
      )

  /** Rebuilds `maven-metadata.xml` from store state, publishing nothing else. Required after deleting versions, and usable without a
    * staging tree.
    *
    * Takes no [[PublishOptions]]: nothing in them applies to a run that uploads no artifacts, now that retry belongs to the store.
    */
  def republishMetadata(
      store: ObjectStore,
      artifactPaths: Seq[String],
      target: PublishTarget = PublishTarget()
  ): Either[PublishError, Seq[String]] =
    guarding("republish-metadata") {
      for
        meta <- publishMetadata(store, artifactPaths, target)
        _ <- purge(target, meta)
      yield meta.map(_.key)
    }

  def artifactsUnder(
      store: ObjectStore,
      target: PublishTarget,
      groupId: String
  ): Either[PublishError, Seq[String]] =
    guarding("listing artifacts") {
      val groupPath = groupId.replace('.', '/')
      Right(store.childPrefixes(target.key(groupPath)).map(a => s"$groupPath/$a"))
    }

  /** Checked before anything is written, because a POM published under coordinates it does not itself declare cannot be corrected
    * afterwards.
    *
    * A coordinate that cannot be read out of the file is refused rather than waved through: an unverifiable claim is exactly the kind this
    * store cannot take back. Nothing legitimate is caught by that, since a POM whose own coordinates do not resolve from its own text is
    * one no registry accepts either - which is what Maven's flatten step exists to fix.
    */
  private def verifyCoordinates(v: VersionDir): Option[PublishError] =
    val pom = v.directory.resolve(v.coordinates.fileName(".pom"))
    PomCoordinates.read(pom) match
      case Left(reason)    => Some(PublishError.InvalidPom(pom, s"it is not readable as XML: $reason"))
      case Right(declared) =>
        def check(field: String, found: PomCoordinates.Coordinate, expected: String) =
          found match
            case PomCoordinates.Coordinate.Literal(value) if value == expected => None
            case PomCoordinates.Coordinate.Literal(value)                      =>
              Some(PublishError.InvalidPom(pom, s"it declares $field $value, but its path says $expected"))
            case PomCoordinates.Coordinate.Unresolved(raw) =>
              Some(
                PublishError.InvalidPom(
                  pom,
                  s"its $field is $raw, which resolves only against a build model this does not have"
                )
              )
            case PomCoordinates.Coordinate.Absent =>
              Some(
                PublishError.InvalidPom(pom, s"it states no $field, and inherits none from a <parent>")
              )
        check("groupId", declared.groupId, v.coordinates.groupId)
          .orElse(check("artifactId", declared.artifactId, v.coordinates.artifactId))
          .orElse(check("version", declared.version, v.coordinates.version))

  private def planVersion(
      v: VersionDir,
      target: PublishTarget,
      options: PublishOptions
  ): Either[PublishError, Seq[PlannedUpload]] =
    val stagedChecksums = v.checksums.map(f => f.getFileName.toString -> f).toMap
    val pomName = v.coordinates.fileName(".pom")

    def upload(name: String, body: Body, size: Long, phase: UploadPhase) =
      val key = target.key(s"${v.coordinates.versionPath}/$name")
      PlannedUpload(
        key,
        body,
        ObjectHeaders(target.policy.contentType(key), target.policy.cacheControl(key)),
        Precondition.IfAbsent,
        size,
        phase
      )

    val perArtifact = v.artifacts.map { artifact =>
      val name = artifact.getFileName.toString
      val staged = Checksums.Algorithm.values.toVector
        .filter(a => stagedChecksums.contains(name + a.suffix))
      val wanted = (Checksums.ForArtifacts ++ (if options.verifyStagedChecksums then staged else Nil)).distinct
      val digests = Checksums.ofFile(artifact, wanted)

      val mismatch =
        if !options.verifyStagedChecksums then None
        else
          staged.flatMap { a =>
            val sidecar = stagedChecksums(name + a.suffix)
            val declared = Checksums.parse(Files.readString(sidecar))
            val actual = digests(a)
            Option.when(!declared.contains(actual))(
              PublishError.ChecksumMismatch(sidecar, a.suffix.drop(1), declared.getOrElse(""), actual)
            )
          }.headOption

      // Computed rather than written back, so publishing never mutates its input.
      val generated = Checksums.ForArtifacts
        .filterNot(a => stagedChecksums.contains(name + a.suffix))
        .map { a =>
          val content = (digests(a) + "\n").getBytes("UTF-8")
          upload(name + a.suffix, Body.Bytes(content), content.length.toLong, UploadPhase.Checksums)
        }

      val phase = if name == pomName then UploadPhase.Marker else UploadPhase.Artifacts
      (mismatch, generated, upload(name, Body.FromFile(artifact), Files.size(artifact), phase))
    }

    perArtifact.flatMap(_._1).headOption match
      case Some(e) => Left(e)
      case None    =>
        val staged = v.checksums.map(f => upload(f.getFileName.toString, Body.FromFile(f), Files.size(f), UploadPhase.Checksums))
        Right(staged ++ perArtifact.flatMap(_._2) ++ perArtifact.map(_._3))

  private def metadataArtifacts(versions: Seq[VersionDir]): Seq[String] =
    versions.map(_.coordinates.artifactPath).distinct.sorted

  private def ordered(uploads: Seq[PlannedUpload]): Seq[PlannedUpload] =
    UploadPhase.values.toVector.flatMap(p => uploads.filter(_.phase == p))

  private def execute(
      store: ObjectStore,
      uploads: Seq[PlannedUpload],
      options: PublishOptions
  ): Either[PublishError, Seq[String]] =
    val pending = uploads.filterNot(alreadyUploaded(store, options.ledger, _))
    // One pool for the whole run rather than one per phase, and shut down here because whoever
    // creates it is the only one that can say when the last phase is done with it.
    val pool = Executors.newFixedThreadPool(math.max(1, options.parallelism))
    given ExecutorService = pool
    try uploadPhases(store, pending, options)
    finally
      pool.shutdown()
      pool.awaitTermination(1, TimeUnit.MINUTES).discard

  private def uploadPhases(store: ObjectStore, pending: Seq[PlannedUpload], options: PublishOptions)(using
      ExecutorService
  ): Either[PublishError, Seq[String]] =
    // Each phase drains before the next begins; parallelism within a phase is unordered, so the
    // phase boundary is the only place the ordering guarantee can live.
    UploadPhase.values.toVector.foldLeft(Right(Vector.empty): Either[PublishError, Seq[String]]) { (acc, phase) =>
      acc.flatMap { done =>
        Parallel
          .map(pending.filter(_.phase == phase)) { u =>
            try
              store.put(u.key, u.body, u.headers, u.precondition) match
                case PutOutcome.Written =>
                  options.ledger.record(u.key)
                  Right(u.key)
                case PutOutcome.PreconditionFailed =>
                  reconcile(store, u).map { key => options.ledger.record(key); key }
            // The store has already exhausted its own retries by the time anything reaches here.
            catch case NonFatal(t) => Left(PublishError.UploadFailed(u.key, t))
          }
          .map(done ++ _)
      }
    }

  /** A ledger records what a run believed it uploaded; only the store knows what is actually there. Skipping on the ledger alone reports a
    * successful publish for objects that a deleted bucket, a different destination or a failed write means nobody can resolve. One HEAD is
    * still far cheaper than re-sending the body, which is all the ledger was ever saving.
    */
  private def alreadyUploaded(store: ObjectStore, ledger: Ledger, upload: PlannedUpload): Boolean =
    ledger.contains(upload.key) && store.head(upload.key).exists(_.size == upload.size)

  private def publishMetadata(
      store: ObjectStore,
      artifactPaths: Seq[String],
      target: PublishTarget
  ): Either[PublishError, Seq[MetadataWrite]] =
    val results = artifactPaths.map(writeMetadata(store, _, target))
    results.collectFirst { case Left(e) => e } match
      case Some(e) => Left(e)
      case None    => Right(results.collect { case Right(Some(w)) => w })

  private def writeMetadata(
      store: ObjectStore,
      artifactPath: String,
      target: PublishTarget
  ): Either[PublishError, Option[MetadataWrite]] =
    val metadataKey = target.key(s"$artifactPath/${Coordinates.MetadataFileName}")
    val idx = artifactPath.lastIndexOf('/')
    val groupId = artifactPath.substring(0, idx).replace('/', '.')
    val artifactId = artifactPath.substring(idx + 1)

    @tailrec def attempt(remaining: Int): Either[PublishError, Option[MetadataWrite]] =
      // Read immediately before the write, and write only if nothing has changed since: two
      // publishers listing concurrently would otherwise each persist a list missing the other's
      // versions, and the later write would win silently.
      val current = store.head(metadataKey)
      val versions = publishedVersions(store, target, artifactPath, artifactId)
      // No versions and no metadata is simply an artifact this store has never held. No versions
      // but existing metadata is the case the repair path exists for: every version was deleted,
      // and the file still advertises them.
      if versions.isEmpty && current.isEmpty then Right(None)
      else
        val xml = MavenMetadata
          .render(groupId, artifactId, versions, Instant.now())
          .getBytes("UTF-8")
        // A store that reports no ETag cannot offer the guard at all. Overwriting anyway would
        // quietly restore the lost update this loop exists to prevent, so refuse instead.
        val precondition = current match
          case Some(o) => o.eTag.map(Precondition.IfMatch(_))
          case None    => Some(Precondition.IfAbsent)

        precondition match
          case None        => Left(PublishError.MissingETag(metadataKey))
          case Some(guard) =>
            val headers =
              ObjectHeaders(target.policy.contentType(metadataKey), target.policy.cacheControl(metadataKey))

            val outcome =
              try Right(store.put(metadataKey, Body.Bytes(xml), headers, guard))
              catch case NonFatal(t) => Left(PublishError.UploadFailed(metadataKey, t))

            outcome match
              case Left(e)                                               => Left(e)
              case Right(PutOutcome.PreconditionFailed) if remaining > 1 => attempt(remaining - 1)
              case Right(PutOutcome.PreconditionFailed)                  => Left(PublishError.MetadataConflict(metadataKey))
              case Right(PutOutcome.Written)                             =>
                writeSidecars(store, metadataKey, xml, target) match
                  case Left(e)        => Left(e)
                  case Right(written) =>
                    if metadataUnchanged(store, metadataKey, xml) then Right(written)
                    else if remaining > 1 then attempt(remaining - 1)
                    else Left(PublishError.MetadataConflict(metadataKey))

    attempt(MaxMetadataAttempts)

  private def writeSidecars(
      store: ObjectStore,
      metadataKey: String,
      xml: Array[Byte],
      target: PublishTarget
  ): Either[PublishError, Option[MetadataWrite]] =
    val writes = Checksums.ForMetadata.map { a =>
      val key = metadataKey + a.suffix
      val headers = ObjectHeaders(target.policy.contentType(key), target.policy.cacheControl(key))
      try
        store.put(key, Body.Bytes(Checksums.content(a, xml)), headers, Precondition.Unconditional).discard
        Right(key)
      catch case NonFatal(t) => Left(PublishError.UploadFailed(key, t))
    }

    writes.collectFirst { case Left(e) => e } match
      case Some(e) => Left(e)
      case None    => Right(Some(MetadataWrite(metadataKey, writes.collect { case Right(k) => k })))

  /** Whether the metadata object still holds exactly what this run wrote.
    *
    * The sidecars are written after the guarded XML rather than with it, so a publisher that lost the race in between would leave checksums
    * describing a version list that is no longer there
    *   - and unlike a lost update, nothing later repairs that on its own. Checking afterwards turns it into another round of the same loop.
    */
  private def metadataUnchanged(store: ObjectStore, key: String, written: Array[Byte]): Boolean =
    storedDigest(store, key, Checksums.Algorithm.Sha256) match
      case Some(stored) => stored == Checksums.hex(Checksums.Algorithm.Sha256, written)
      // A store that cannot be read back offers nothing to check against.
      case None => true

  /** Versions a resolver can actually use: those whose POM has landed. A bare child prefix may be a version an interrupted run left
    * half-uploaded, and listing it in metadata would advertise artifacts that are not there.
    */
  private def publishedVersions(
      store: ObjectStore,
      target: PublishTarget,
      artifactPath: String,
      artifactId: String
  ): Seq[String] =
    val base = target.key(artifactPath) + "/"
    store
      .keys(base)
      .flatMap { key =>
        val rest = key.substring(base.length)
        val slash = rest.indexOf('/')
        if slash < 0 then None
        else
          val version = rest.substring(0, slash)
          Option.when(rest.substring(slash + 1) == s"$artifactId-$version.pom")(version)
      }
      .distinct

  /** An existing key is not proof that someone else wrote it: a response lost after the store committed looks exactly the same from here,
    * and the retry then finds the key it just wrote. Identical content is accepted whoever put it there, since the repository ends up
    * holding the right bytes either way; different content is a real conflict.
    */
  private def reconcile(store: ObjectStore, upload: PlannedUpload): Either[PublishError, String] =
    store.head(upload.key) match
      case None =>
        Left(PublishError.AmbiguousWrite(upload.key, "the write was refused but the store holds no such key"))
      case Some(stored) if stored.size != upload.size => Left(PublishError.ConcurrentWrite(upload.key))
      // An ETag equal to the content MD5 settles it; anything else settles nothing, so only the
      // object itself can rule a conflict in.
      case Some(stored) if eTagDigest(stored).contains(localDigest(upload.body, Checksums.Algorithm.Md5)) =>
        Right(upload.key)
      case Some(_) =>
        storedDigest(store, upload.key, Checksums.Algorithm.Sha256) match
          case Some(sha) if sha == localDigest(upload.body, Checksums.Algorithm.Sha256) =>
            Right(upload.key)
          case Some(_) => Left(PublishError.ConcurrentWrite(upload.key))
          case None    =>
            Left(
              PublishError.AmbiguousWrite(
                upload.key,
                "the stored object neither matches by ETag nor can be read back to compare"
              )
            )

  private def localDigest(body: Body, algorithm: Checksums.Algorithm): String = body match
    case Body.Bytes(value)   => Checksums.hex(algorithm, value)
    case Body.FromFile(path) => Checksums.ofFile(path, Seq(algorithm))(algorithm)

  private def storedDigest(
      store: ObjectStore,
      key: String,
      algorithm: Checksums.Algorithm
  ): Option[String] =
    store.read(key).map { in =>
      try Checksums.ofStream(in, Seq(algorithm))(algorithm)
      finally in.close()
    }

  /** An S3 ETag is the content MD5 only for an object written in a single unencrypted request. Encryption with KMS, a directory bucket or a
    * multipart upload all replace it with an opaque value that can still be 32 hex characters, so its shape proves nothing: a match is a
    * cheap confirmation, a mismatch means only that this shortcut did not apply.
    */
  private def eTagDigest(stored: StoredObject): Option[String] =
    stored.eTag.map(_.replace("\"", "").toLowerCase).filter(_.matches("[0-9a-f]{32}"))

  private def purge(target: PublishTarget, metadata: Seq[MetadataWrite]): Either[PublishError, Seq[String]] =
    (target.publicUrl, target.purger) match
      case (Some(base), Some(purger)) if metadata.nonEmpty =>
        // The sidecars go too: a fresh version list served beside a stale checksum fails
        // verification just as surely as stale XML would.
        val urls = metadata.flatMap(_.keys).map(k => s"${base.stripSuffix("/")}/$k")
        purger.purge(urls).left.map(PublishError.PurgeFailed(_)).map(_ => urls)
      case _ => Right(Seq.empty)

  private def guarding[A](operation: String)(op: => Either[PublishError, A]): Either[PublishError, A] =
    try op
    catch case NonFatal(t) => Left(PublishError.StoreFailure(operation, t))
