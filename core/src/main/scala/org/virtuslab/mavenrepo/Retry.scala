package org.virtuslab.mavenrepo

import scala.util.boundary
import scala.util.boundary.break
import scala.util.control.NonFatal

/** Bounded retry with exponential backoff.
  *
  * Deliberately generic: it knows nothing about object stores. The classifier decides what is worth another attempt, because only whatever
  * raised a failure can tell a throttled request from a rejected credential - and retrying the second kind just spends the whole backoff
  * before returning the same error.
  */
object Retry:

  /** @param maxRetries
    *   attempts *after* the first, so `0` means try once and give up
    */
  final case class Policy(maxRetries: Int = 5, initialBackoffMillis: Long = 1000L, maxBackoffMillis: Long = 16000L):

    /** Doubles from the initial delay and then holds. Object stores throttle on total in-flight requests, so backing off is the only useful
      * response to a burst of failures; capping keeps a long tail from stalling a run outright.
      */
    def backoffMillis(attempt: Int): Long = math.min(initialBackoffMillis * (1L << (attempt - 1)), maxBackoffMillis)

  object Policy:
    /** One attempt, no retry. */
    val never: Policy = Policy(maxRetries = 0)

  /** Runs `op`, retrying while `retryable` accepts the failure and attempts remain. Rethrows the last failure once they run out, so a
    * caller sees the same exception it would have seen without any retrying at all.
    */
  def apply[A](policy: Policy, retryable: Throwable => Boolean)(op: => A): A =
    boundary:
      var attempt = 0
      var last: Throwable = IllegalStateException("retry ran no attempt")
      while attempt <= policy.maxRetries do
        // The outcome is captured before leaving, and `break` is called outside the `try`.
        // `break` signals by throwing, and that throw is NonFatal, so `try break(op) catch case
        // NonFatal(_)` would catch its own success and retry an operation that had just worked.
        val outcome =
          try Right(op)
          catch case NonFatal(t) => Left(t)

        outcome match
          case Right(value) => break(value)
          case Left(t)      =>
            last = t
            if !retryable(t) then throw t
            attempt += 1
            if attempt <= policy.maxRetries then Thread.sleep(policy.backoffMillis(attempt))
      throw last
