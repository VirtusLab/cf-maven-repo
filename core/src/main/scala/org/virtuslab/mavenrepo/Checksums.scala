package org.virtuslab.mavenrepo

import java.io.InputStream
import java.nio.file.{Files, Path}
import java.security.MessageDigest

object Checksums:

  val ForArtifacts: Seq[Algorithm] = Seq(Algorithm.Sha1, Algorithm.Md5)

  val ForMetadata: Seq[Algorithm] =
    Seq(Algorithm.Sha1, Algorithm.Md5, Algorithm.Sha256, Algorithm.Sha512)

  enum Algorithm(val jdkName: String, val suffix: String):
    case Sha1 extends Algorithm("SHA-1", ".sha1")
    case Md5 extends Algorithm("MD5", ".md5")
    case Sha256 extends Algorithm("SHA-256", ".sha256")
    case Sha512 extends Algorithm("SHA-512", ".sha512")

  val Suffixes: Set[String] = Algorithm.values.map(_.suffix).toSet

  def isChecksum(name: String): Boolean = Suffixes.exists(name.endsWith)

  def algorithmOf(name: String): Option[Algorithm] =
    Algorithm.values.find(a => name.endsWith(a.suffix))

  def hex(algorithm: Algorithm, bytes: Array[Byte]): String =
    render(MessageDigest.getInstance(algorithm.jdkName).digest(bytes))

  def content(algorithm: Algorithm, bytes: Array[Byte]): Array[Byte] =
    (hex(algorithm, bytes) + "\n").getBytes("UTF-8")

  /** Digests a file for several algorithms in a single streaming pass, so an artifact is never held in memory and is never read more than
    * once.
    */
  def ofFile(path: Path, algorithms: Seq[Algorithm]): Map[Algorithm, String] =
    if algorithms.isEmpty then Map.empty
    else
      val in = Files.newInputStream(path)
      try ofStream(in, algorithms)
      finally in.close()

  /** As [[ofFile]], for content that is not on disk. The caller owns the stream. */
  def ofStream(in: InputStream, algorithms: Seq[Algorithm]): Map[Algorithm, String] =
    if algorithms.isEmpty then Map.empty
    else
      val digests = algorithms.map(a => a -> MessageDigest.getInstance(a.jdkName))
      val buffer = new Array[Byte](64 * 1024)
      var read = in.read(buffer)
      while read >= 0 do
        digests.foreach((_, d) => d.update(buffer, 0, read))
        read = in.read(buffer)
      digests.map((a, d) => a -> render(d.digest())).toMap

  /** A checksum sidecar is the bare hex digest; tools differ on trailing whitespace and some append the file name, so only the first token
    * is significant.
    */
  def parse(sidecar: String): Option[String] =
    sidecar.trim.split("\\s+").headOption.map(_.toLowerCase).filter(_.nonEmpty)

  private def render(digest: Array[Byte]): String = digest.map("%02x".format(_)).mkString
