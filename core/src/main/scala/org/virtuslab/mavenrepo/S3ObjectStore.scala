package org.virtuslab.mavenrepo

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.core.exception.{SdkException, SdkServiceException}
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*

import java.net.URI
import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*

/** [[ObjectStore]] over anything speaking the S3 API.
  *
  * Every operation is retried, not only writes: a listing or a HEAD that fails mid-run fails the run just as surely as a failed PUT, and
  * the store is the one place that can classify its own errors.
  */
final class S3ObjectStore(client: S3Client, bucket: String, retry: Retry.Policy) extends ObjectStore:

  private def attempt[A](op: => A): A = Retry(retry, S3ObjectStore.isTransient)(op)

  def head(key: String): Option[StoredObject] = attempt {
    try
      val r = client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build())
      Some(StoredObject(key, r.contentLength(), Option(r.eTag())))
    catch
      case _: NoSuchKeyException => None
      // HEAD carries no response body, so some implementations surface a miss as a bare 404
      // rather than the modelled NoSuchKey error.
      case e: S3Exception if e.statusCode() == 404 => None
  }

  def put(key: String, body: Body, headers: ObjectHeaders, precondition: Precondition): PutOutcome = attempt {
    val builder = PutObjectRequest
      .builder()
      .bucket(bucket)
      .key(key)
      .contentType(headers.contentType)
      .cacheControl(headers.cacheControl)

    precondition match
      case Precondition.Unconditional => ()
      case Precondition.IfAbsent      => builder.ifNoneMatch("*").discard
      case Precondition.IfMatch(eTag) => builder.ifMatch(eTag).discard

    val requestBody = body match
      case Body.Bytes(value)   => RequestBody.fromBytes(value)
      case Body.FromFile(path) => RequestBody.fromFile(path)

    try
      client.putObject(builder.build(), requestBody).discard
      PutOutcome.Written
    catch
      case e: S3Exception if e.statusCode() == 412 => PutOutcome.PreconditionFailed
      // If-Match against a key that has since been deleted fails as a miss, not as 412.
      case e: S3Exception if e.statusCode() == 404 && precondition.isInstanceOf[Precondition.IfMatch] =>
        PutOutcome.PreconditionFailed
  }

  def childPrefixes(prefix: String): Seq[String] =
    val normalised = if prefix.endsWith("/") then prefix else prefix + "/"
    paginate(normalised, delimiter = Some("/")) { page =>
      page.commonPrefixes().asScala.toVector.map { cp =>
        cp.prefix().stripSuffix("/").substring(normalised.length)
      }
    }

  def keys(prefix: String): Seq[String] =
    paginate(prefix, delimiter = None)(_.contents().asScala.toVector.map(_.key()))

  def delete(key: String): Unit = attempt {
    client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build()).discard
  }

  override def read(key: String): Option[java.io.InputStream] = attempt {
    try Some(client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build()))
    catch
      case _: NoSuchKeyException                   => None
      case e: S3Exception if e.statusCode() == 404 => None
  }

  override def close(): Unit = client.close()

  private def paginate[A](prefix: String, delimiter: Option[String])(
      extract: ListObjectsV2Response => Seq[A]
  ): Seq[A] = attempt {
    @tailrec
    def pages(token: Option[String], found: Vector[A]): Vector[A] =
      val b = ListObjectsV2Request.builder().bucket(bucket).prefix(prefix)
      delimiter.foreach(d => b.delimiter(d).discard)
      token.foreach(t => b.continuationToken(t).discard)
      val page = client.listObjectsV2(b.build())
      val soFar = found ++ extract(page)
      if page.isTruncated then pages(Option(page.nextContinuationToken()), soFar) else soFar

    pages(None, Vector.empty).distinct.sorted(using Ordering.by(_.toString))
  }

object S3ObjectStore:

  /** Throttling, transport failures and server errors are worth another attempt; a rejected request, a rejected credential or a missing
    * bucket will fail again identically.
    */
  private[mavenrepo] def isTransient(failure: Throwable): Boolean = failure match
    // 409 on a conditional write is contention, which AWS documents as retryable; a definitive
    // precondition failure comes back as 412 and is not an exception at all.
    case e: SdkServiceException =>
      e.statusCode() == 429 || e.statusCode() == 409 || e.statusCode() >= 500
    // Everything else the SDK raises is client-side: a connection reset, a read timeout, a DNS
    // failure. Those are the ones worth another attempt.
    case _: SdkException        => true
    case _: java.io.IOException => true
    case _                      => false

  /** R2 and most other S3-compatible stores ignore the region but require one to be present. */
  val CompatibilityRegion: String = "auto"

  /** @param endpoint
    *   e.g. `https://<account>.r2.cloudflarestorage.com`; omit for AWS S3, where `AWS_ENDPOINT_URL_S3` is honoured in its place
    * @param region
    *   left to the SDK's default chain when empty, which is what real AWS S3 needs
    * @param pathStyle
    *   addresses the bucket as a path segment rather than as a subdomain. The SDK uses subdomains even behind a custom endpoint, which
    *   turns `http://localhost:9000` into `http://bucket.localhost:9000` and fails to resolve; MinIO and most self-hosted stores need this
    *   on.
    * @param credentials
    *   left to the SDK's default chain when empty, which is how the CLI runs it.
    * @param retry
    *   applied to every operation. The SDK retries some failures of its own beneath this, so the two compound; lower it rather than raise
    *   it if a flaky store is taking too long to give up.
    */
  def apply(
      bucket: String,
      endpoint: Option[String],
      region: Option[String] = None,
      pathStyle: Boolean = false,
      credentials: Option[AwsCredentialsProvider] = None,
      retry: Retry.Policy = Retry.Policy()
  ): S3ObjectStore =
    val builder = S3Client.builder().httpClient(UrlConnectionHttpClient.create())
    if pathStyle then builder.forcePathStyle(true).discard
    // A custom endpoint means a non-AWS store, which has no meaningful region of its own; real
    // AWS S3 gets its region from AWS_REGION or the profile, so it must not be forced here.
    val redirected = endpoint.orElse(sys.env.get("AWS_ENDPOINT_URL_S3")).filter(_.nonEmpty)
    region.orElse(redirected.map(_ => CompatibilityRegion)).foreach { r =>
      builder.region(Region.of(r)).discard
    }
    endpoint.foreach(e => builder.endpointOverride(URI.create(e)).discard)
    credentials.foreach(c => builder.credentialsProvider(c).discard)
    new S3ObjectStore(builder.build(), bucket, retry)
