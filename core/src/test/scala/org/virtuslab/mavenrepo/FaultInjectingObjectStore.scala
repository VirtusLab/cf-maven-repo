package org.virtuslab.mavenrepo

import java.io.InputStream
import scala.collection.mutable

/** Wraps a store and fails chosen operations on demand, so an interrupted publish can be staged deterministically instead of waited for.
  *
  * A rule can commit its operation before failing, which is what a response lost in flight looks like from the caller's side: the store did
  * the work and the caller never found out.
  */
object FaultInjectingObjectStore:
  enum Operation:
    case Head, Put, Keys, ChildPrefixes, Delete, Read

final class FaultInjectingObjectStore(delegate: ObjectStore) extends ObjectStore:

  import FaultInjectingObjectStore.Operation

  private final class Rule(
      val operation: Operation,
      val matches: String => Boolean,
      val skip: Int,
      val times: Int,
      val commitFirst: Boolean,
      val failure: () => Throwable
  ):
    var seen = 0
    var fired = 0

  private val rules = mutable.Buffer.empty[Rule]
  private val counts = mutable.Map.empty[Operation, Int].withDefaultValue(0)
  private val perKey = mutable.Map.empty[(Operation, String), Int].withDefaultValue(0)

  /** @param skip
    *   let this many matching calls through before failing
    * @param times
    *   fail this many matching calls, then stop interfering
    * @param commitFirst
    *   perform the operation and fail afterwards, losing the response
    */
  def failOn(
      operation: Operation,
      matches: String => Boolean = _ => true,
      skip: Int = 0,
      times: Int = Int.MaxValue,
      commitFirst: Boolean = false,
      failure: () => Throwable = () => java.io.IOException("injected failure")
  ): this.type =
    rules += Rule(operation, matches, skip, times, commitFirst, failure)
    this

  def callsTo(operation: Operation): Int = counts(operation)

  /** Attempts against one key, which is what a retry policy is actually about. */
  def callsTo(operation: Operation, key: String): Int = perKey((operation, key))

  /** Retry belongs to the store, so a fault-injected store has to do its own - which is also what makes the attempt counts below mean
    * anything: every retry is another call through here.
    */
  private def guarded[A](operation: Operation, key: String)(op: => A): A =
    Retry(TestRetry.policy(maxRetries), _ => retryable)(attemptOnce(operation, key)(op))

  private def attemptOnce[A](operation: Operation, key: String)(op: => A): A =
    counts(operation) = counts(operation) + 1
    perKey((operation, key)) = perKey((operation, key)) + 1
    rules.find(r => r.operation == operation && r.fired < r.times && r.matches(key)) match
      case Some(rule) if rule.seen >= rule.skip =>
        rule.fired += 1
        if rule.commitFirst then op.discard
        throw rule.failure()
      case Some(rule) =>
        rule.seen += 1
        op
      case None => op

  def head(key: String): Option[StoredObject] = guarded(Operation.Head, key)(delegate.head(key))

  def put(key: String, body: Body, headers: ObjectHeaders, precondition: Precondition): PutOutcome =
    guarded(Operation.Put, key)(delegate.put(key, body, headers, precondition))

  def childPrefixes(prefix: String): Seq[String] =
    guarded(Operation.ChildPrefixes, prefix)(delegate.childPrefixes(prefix))

  def keys(prefix: String): Seq[String] = guarded(Operation.Keys, prefix)(delegate.keys(prefix))

  def delete(key: String): Unit = guarded(Operation.Delete, key)(delegate.delete(key))

  override def read(key: String): Option[InputStream] =
    guarded(Operation.Read, key)(delegate.read(key))

  /** Injected failures stand in for transient ones only when a test says so; by default nothing here is retried. */
  var retryable: Boolean = false

  /** Attempts after the first, the way [[Retry.Policy]] counts them. */
  var maxRetries: Int = 0
