package org.virtuslab.mavenrepo

import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

/** An [[ObjectStore]] backed by a map, for testing publishing code without a real bucket. */
final class InMemoryObjectStore extends ObjectStore:

  private val contents = ConcurrentHashMap[String, Array[Byte]]()
  private val headers = ConcurrentHashMap[String, ObjectHeaders]()
  private val log = java.util.Collections.synchronizedList(java.util.ArrayList[String]())

  def head(key: String): Option[StoredObject] =
    Option(contents.get(key)).map(b => StoredObject(key, b.length.toLong, Some(eTagOf(b))))

  /** Preconditions are evaluated under a lock so that, as on a real store, two concurrent writers cannot both observe the guard as
    * satisfied.
    */
  def put(key: String, body: Body, hdrs: ObjectHeaders, precondition: Precondition): PutOutcome =
    val bytes = body match
      case Body.Bytes(value)   => value
      case Body.FromFile(path) => Files.readAllBytes(path)

    synchronized {
      val current = Option(contents.get(key))
      val satisfied = precondition match
        case Precondition.Unconditional => true
        case Precondition.IfAbsent      => current.isEmpty
        case Precondition.IfMatch(tag)  => current.exists(b => eTagOf(b) == tag)
      if !satisfied then PutOutcome.PreconditionFailed
      else
        contents.put(key, bytes).discard
        headers.put(key, hdrs).discard
        log.add(key).discard
        PutOutcome.Written
    }

  def childPrefixes(prefix: String): Seq[String] =
    val normalised = if prefix.endsWith("/") then prefix else prefix + "/"
    contents
      .keySet()
      .asScala
      .filter(_.startsWith(normalised))
      .map(_.substring(normalised.length).takeWhile(_ != '/'))
      .filter(_.nonEmpty)
      .toVector
      .distinct
      .sorted

  def keys(prefix: String): Seq[String] =
    contents.keySet().asScala.filter(_.startsWith(prefix)).toVector.sorted

  def delete(key: String): Unit =
    contents.remove(key).discard
    headers.remove(key).discard

  override def read(key: String): Option[java.io.InputStream] =
    Option(contents.get(key)).map(java.io.ByteArrayInputStream(_))

  def headersOf(key: String): Option[ObjectHeaders] = Option(headers.get(key))

  def bytesOf(key: String): Option[Array[Byte]] = Option(contents.get(key))

  def stringOf(key: String): Option[String] = bytesOf(key).map(String(_, "UTF-8"))

  /** Keys in write order, for asserting upload ordering. */
  def putLog: Seq[String] = log.asScala.toVector

  def size: Int = contents.size

  private def eTagOf(bytes: Array[Byte]): String =
    "\"" + Checksums.hex(Checksums.Algorithm.Md5, bytes) + "\""
