package org.virtuslab.mavenrepo.cloudflare

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import org.virtuslab.mavenrepo.CachePurger
import sttp.client4.*

import scala.util.control.NonFatal

private[cloudflare] final case class PurgeRequest(files: List[String])
private[cloudflare] final case class ApiError(code: Int, message: String)
private[cloudflare] final case class PurgeResponse(success: Boolean, errors: List[ApiError])

private[cloudflare] object Codecs:
  given JsonValueCodec[PurgeRequest] = JsonCodecMaker.make
  // The response carries more than is modelled here.
  given JsonValueCodec[PurgeResponse] =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(true))

/** Purges Cloudflare's edge cache.
  *
  * Requires an API token with Cache Purge on the zone. This is a bearer-token call against the Cloudflare API, so it cannot reuse the S3
  * credentials used for uploads.
  */
final class CloudflarePurger(
    zoneId: String,
    apiToken: String,
    backend: SyncBackend = DefaultSyncBackend(),
    maxAttempts: Int = 4
) extends CachePurger:

  import Codecs.given

  def purge(urls: Seq[String]): Either[String, Unit] =
    val batches = urls.grouped(CloudflarePurger.MaxUrlsPerRequest).toVector
    batches.foldLeft(Right(()): Either[String, Unit]) { (acc, batch) =>
      acc.flatMap(_ => purgeBatch(batch))
    }

  private def purgeBatch(urls: Seq[String]): Either[String, Unit] =
    def go(attempt: Int): Either[String, Unit] =
      // The network call and the decode both throw; a failed purge must not take down a publish
      // run that has already written every object correctly.
      val outcome =
        try send(urls)
        catch
          case NonFatal(t) =>
            CloudflarePurger.Outcome.Retry(s"purge request failed: ${CloudflarePurger.describe(t)}")

      outcome match
        case CloudflarePurger.Outcome.Done(result)                            => result
        case CloudflarePurger.Outcome.Retry(message) if attempt < maxAttempts =>
          // Doubling from 500ms and then holding at 8s: 500, 1000, 2000, 4000, 8000, 8000...
          // `attempt` is 1-based, so the shift is by `attempt - 1` and the first wait is the
          // initial delay itself. The cap matters because purging is the last step of a run that
          // has already written every object correctly - waiting minutes to retry it would hold a
          // release open over work that is finished.
          Thread.sleep(math.min(500L * (1L << (attempt - 1)), 8000L))
          go(attempt + 1)
        case CloudflarePurger.Outcome.Retry(message) => Left(message)

    go(1)
  end purgeBatch

  /** Throttling and server errors are worth another attempt; a rejected token or a malformed request will be rejected identically however
    * many times it is sent.
    */
  private def send(urls: Seq[String]): CloudflarePurger.Outcome =
    val response = basicRequest
      .post(uri"https://api.cloudflare.com/client/v4/zones/$zoneId/purge_cache")
      .header("Authorization", s"Bearer $apiToken")
      .contentType("application/json")
      .body(writeToArray(PurgeRequest(urls.toList)))
      .send(backend)

    val status = response.code.code
    response.body match
      case Left(error) if status == 429 || status >= 500 =>
        CloudflarePurger.Outcome.Retry(s"purge failed (HTTP $status): $error")
      case Left(error) => CloudflarePurger.Outcome.Done(Left(s"purge failed (HTTP $status): $error"))
      case Right(body) =>
        val parsed = readFromString[PurgeResponse](body)
        if parsed.success then CloudflarePurger.Outcome.Done(Right(()))
        else CloudflarePurger.Outcome.Done(Left(parsed.errors.map(e => s"${e.code} ${e.message}").mkString("; ")))

object CloudflarePurger:

  /** Cloudflare's documented cap for purge by URL. */
  val MaxUrlsPerRequest: Int = 30

  /** The client wraps transport failures in an exception naming only the request, so the reason - a timeout, a refused connection, an
    * unresolvable host - is one or more causes down.
    */
  private[cloudflare] def describe(failure: Throwable): String =
    Iterator
      .iterate(failure)(_.getCause)
      .takeWhile(_ != null)
      // Bounded because a cause chain is not guaranteed to end: `initCause` can build a cycle,
      // and this runs while reporting a failure, which is the worst place to hang. Five is past
      // the depth any of these wrappers actually reach, so nothing informative is lost.
      .take(5)
      .flatMap(t => Option(t.getMessage))
      .toVector
      .distinct
      .mkString(": ")

  private[cloudflare] enum Outcome:
    case Done(result: Either[String, Unit])
    case Retry(message: String)

  def fromEnv(zoneId: String): Either[String, CloudflarePurger] =
    sys.env.get("CLOUDFLARE_API_TOKEN") match
      case Some(token) => Right(CloudflarePurger(zoneId, token))
      case None        => Left("CLOUDFLARE_API_TOKEN is not set")
