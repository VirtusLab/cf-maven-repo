package org.virtuslab.mavenrepo

import java.nio.file.Path

trait ObjectStore extends AutoCloseable:

  def head(key: String): Option[StoredObject]

  def put(key: String, body: Body, headers: ObjectHeaders, precondition: Precondition): PutOutcome

  /** Immediate "directory" names under a prefix. */
  def childPrefixes(prefix: String): Seq[String]

  /** Every key under a prefix, recursively. */
  def keys(prefix: String): Seq[String]

  def delete(key: String): Unit

  /** Opens the stored content, or `None` if the key does not exist. Used only to establish that a refused write matches what is already
    * stored; a store that cannot serve this reports that case as unresolvable rather than guessing.
    *
    * The caller closes the stream.
    */
  def read(key: String): Option[java.io.InputStream] = None

  /** A store retries its own transient failures. Nothing above this trait does, because only an implementation can tell a throttled request
    * from a rejected credential, and the retry has to sit under the whole operation rather than around one method of it. See [[Retry]] for
    * the mechanism and [[S3ObjectStore]] for what an implementation classifies.
    */
  def close(): Unit = ()

final case class StoredObject(key: String, size: Long, eTag: Option[String])

final case class ObjectHeaders(contentType: String, cacheControl: String)

/** Guard evaluated by the store as part of the write, so concurrent publishers cannot both win. An existence check followed by an
  * unconditional write would race.
  */
enum Precondition:
  case Unconditional

  /** `If-None-Match: *` — the key must not exist. */
  case IfAbsent

  /** `If-Match` — the key must still hold exactly this version. */
  case IfMatch(eTag: String)

enum Body:
  case Bytes(value: Array[Byte])

  /** Streamed, so a large artifact is never held in memory. */
  case FromFile(path: Path)

enum PutOutcome:
  case Written

  /** The store rejected the write because the [[Precondition]] no longer held. */
  case PreconditionFailed
