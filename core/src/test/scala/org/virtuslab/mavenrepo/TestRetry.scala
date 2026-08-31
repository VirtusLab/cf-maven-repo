package org.virtuslab.mavenrepo

/** Retry settings for tests: the production mechanism, without the wall-clock wait. These suites assert how many attempts were made, never
  * how long they took.
  */
object TestRetry:

  def policy(maxRetries: Int): Retry.Policy =
    Retry.Policy(maxRetries, initialBackoffMillis = 1L, maxBackoffMillis = 1L)

  /** A store that treats every failure it raises as transient. */
  val anything: Throwable => Boolean = _ => true
