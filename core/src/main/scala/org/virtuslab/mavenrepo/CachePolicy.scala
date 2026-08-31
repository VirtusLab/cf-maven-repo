package org.virtuslab.mavenrepo

/** Headers applied to uploaded objects.
  *
  * Artifacts are immutable once published and can be cached indefinitely; metadata is rewritten whenever a version lands and must not be.
  * Caching metadata for long makes version ranges resolve against a stale version list for the lifetime of the entry.
  *
  * `isMutable` is a parameter because a repository may keep additional mutable prefixes of its own; the default covers only
  * `maven-metadata.xml`.
  */
final case class CachePolicy(
    immutableCacheControl: String = CachePolicy.DefaultImmutable,
    mutableCacheControl: String = CachePolicy.DefaultMutable,
    isMutable: String => Boolean = CachePolicy.isMetadataKey,
    contentTypes: Map[String, String] = CachePolicy.DefaultContentTypes,
    fallbackContentType: String = "application/octet-stream"
):

  def cacheControl(key: String): String =
    if isMutable(key) then mutableCacheControl else immutableCacheControl

  def contentType(key: String): String =
    val name = key.substring(key.lastIndexOf('/') + 1)
    val dot = name.lastIndexOf('.')
    if dot < 0 then fallbackContentType
    else contentTypes.getOrElse(name.substring(dot).toLowerCase, fallbackContentType)

object CachePolicy:

  val DefaultImmutable: String = "public, max-age=31536000, immutable"
  val DefaultMutable: String = "public, max-age=60, must-revalidate"

  /** Matches `maven-metadata.xml` and its checksum sidecars exactly, so an artifact whose own file name merely begins with it stays
    * immutable.
    */
  def isMetadataKey(key: String): Boolean =
    val name = key.substring(key.lastIndexOf('/') + 1)
    name == Coordinates.MetadataFileName ||
    Checksums.Suffixes.exists(s => name == Coordinates.MetadataFileName + s)

  val DefaultContentTypes: Map[String, String] = Map(
    ".jar" -> "application/java-archive",
    ".pom" -> "text/xml",
    ".xml" -> "text/xml",
    ".json" -> "application/json",
    ".module" -> "application/json",
    ".sha1" -> "text/plain",
    ".sha256" -> "text/plain",
    ".sha512" -> "text/plain",
    ".md5" -> "text/plain",
    ".asc" -> "text/plain"
  )
