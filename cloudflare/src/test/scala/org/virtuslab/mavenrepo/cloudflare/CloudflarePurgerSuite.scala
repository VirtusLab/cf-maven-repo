package org.virtuslab.mavenrepo.cloudflare

import sttp.client4.*
import sttp.client4.testing.{ResponseStub, StubBody, SyncBackendStub}
import sttp.model.{Method, StatusCode}

import scala.collection.mutable

/** The Cloudflare side of a publish, without Cloudflare.
  *
  * Everything here is a claim about how the API responds - batching limits, which failures are worth another attempt, what a body that
  * parses but reports failure means - so a stub backend checks them without a network or a token.
  */
class CloudflarePurgerSuite extends munit.FunSuite:

  private val zone = "zone-id-123"
  private val token = "cf-test-token"

  private def ok(errors: String = "[]") =
    ResponseStub.adjust(s"""{"success":true,"errors":$errors,"messages":[],"result":null}""")

  private def status(code: Int) =
    ResponseStub.adjust("""{"success":false,"errors":[]}""", StatusCode(code))

  /** Serves the given responses in order, repeating the last, and records every request. */
  private def scripted(responses: Response[StubBody]*) =
    val seen = mutable.Buffer.empty[GenericRequest[?, ?]]
    val queue = mutable.Queue(responses*)
    val backend = SyncBackendStub
      .whenRequestMatches { r => seen += r; true }
      .thenRespond(if queue.size > 1 then queue.dequeue() else queue.head)
    (backend, seen)

  private def throwing(failures: Int) =
    var thrown = 0
    val backend = SyncBackendStub.whenAnyRequest.thenRespond {
      if thrown < failures then
        thrown += 1
        throw java.net.SocketTimeoutException("read timed out")
      else ok()
    }
    backend

  private def bodyOf(request: GenericRequest[?, ?]): String = request.body match
    case ByteArrayBody(bytes, _) => String(bytes, "UTF-8")
    case StringBody(value, _, _) => value
    case other                   => fail(s"unexpected body type: $other")

  private def urls(n: Int) = (1 to n).map(i => s"https://maven.example.com/a$i")

  test("a purge posts the urls to the zone, as JSON, with the token as a bearer") {
    val (backend, seen) = scripted(ok())
    assertEquals(CloudflarePurger(zone, token, backend).purge(urls(2)), Right(()))

    assertEquals(seen.size, 1)
    val request = seen.head
    assertEquals(request.method, Method.POST)
    assertEquals(request.uri.toString, s"https://api.cloudflare.com/client/v4/zones/$zone/purge_cache")
    assertEquals(request.header("Authorization"), Some(s"Bearer $token"))
    assertEquals(request.header("Content-Type"), Some("application/json"))
    assertEquals(bodyOf(request), """{"files":["https://maven.example.com/a1","https://maven.example.com/a2"]}""")
  }

  test("30 urls go in one request and 31 are split, in order") {
    val (one, seenOne) = scripted(ok())
    assertEquals(CloudflarePurger(zone, token, one).purge(urls(30)), Right(()))
    assertEquals(seenOne.size, 1)

    val (two, seenTwo) = scripted(ok())
    assertEquals(CloudflarePurger(zone, token, two).purge(urls(31)), Right(()))
    assertEquals(seenTwo.size, 2)
    assert(bodyOf(seenTwo.head).contains("/a30"), "the first batch takes the cap")
    assert(!bodyOf(seenTwo.head).contains("/a31"))
    assertEquals(bodyOf(seenTwo(1)), """{"files":["https://maven.example.com/a31"]}""")
  }

  test("an authentication failure is reported at once, without retrying") {
    Seq(401, 403).foreach { code =>
      val (backend, seen) = scripted(status(code))
      val result = CloudflarePurger(zone, token, backend, maxAttempts = 4).purge(urls(1))
      assert(result.isLeft, result)
      assert(result.left.exists(_.contains(code.toString)), result)
      assertEquals(seen.size, 1, s"$code must not be retried")
    }
  }

  test("throttling and server errors are retried, and succeed if the store recovers") {
    Seq(429, 500, 503).foreach { code =>
      val (backend, seen) = scripted(status(code), ok())
      assertEquals(CloudflarePurger(zone, token, backend, maxAttempts = 3).purge(urls(1)), Right(()))
      assertEquals(seen.size, 2, s"$code should have been retried once")
    }
  }

  test("a failure that never clears gives up after the attempt limit") {
    val (backend, seen) = scripted(status(503))
    val result = CloudflarePurger(zone, token, backend, maxAttempts = 3).purge(urls(1))
    assert(result.isLeft, result)
    assertEquals(seen.size, 3)
  }

  test("a 200 that reports failure is an error, and is not retried") {
    val (backend, seen) = scripted(
      ResponseStub.adjust("""{"success":false,"errors":[{"code":1012,"message":"Request failed"}]}""")
    )
    val result = CloudflarePurger(zone, token, backend, maxAttempts = 4).purge(urls(1))
    assertEquals(result, Left("1012 Request failed"))
    assertEquals(seen.size, 1, "the API answered definitively; repeating it changes nothing")
  }

  test("fields the decoder does not model are ignored") {
    val (backend, _) = scripted(
      ResponseStub.adjust(
        """{"success":true,"errors":[],"messages":[],"result":{"id":"x"},"result_info":{"page":1}}"""
      )
    )
    assertEquals(CloudflarePurger(zone, token, backend).purge(urls(1)), Right(()))
  }

  test("a body that is not JSON fails the purge rather than escaping as an exception") {
    val (backend, _) = scripted(ResponseStub.adjust("<html>gateway</html>"))
    val result = CloudflarePurger(zone, token, backend, maxAttempts = 1).purge(urls(1))
    assert(result.isLeft, result)
    assert(result.left.exists(_.contains("purge request failed")), result)
  }

  test("a transport failure is retried and then reported, never thrown") {
    assertEquals(CloudflarePurger(zone, token, throwing(1), maxAttempts = 3).purge(urls(1)), Right(()))

    val result = CloudflarePurger(zone, token, throwing(99), maxAttempts = 2).purge(urls(1))
    assert(result.isLeft, result)
    assert(result.left.exists(_.contains("read timed out")), result)
  }

  test("a batch that fails stops the ones behind it") {
    val (backend, seen) = scripted(ok(), status(403), ok())
    val result = CloudflarePurger(zone, token, backend, maxAttempts = 1).purge(urls(90))
    assert(result.isLeft, result)
    assertEquals(seen.size, 2, "the third batch must never be sent")
  }
