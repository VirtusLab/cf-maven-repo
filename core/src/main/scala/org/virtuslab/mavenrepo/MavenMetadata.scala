package org.virtuslab.mavenrepo

import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter

/** Renders `maven-metadata.xml`. Written but never parsed, so no XML library is needed. */
object MavenMetadata:

  private val LastUpdatedFormat =
    DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC)

  /** @param versions every version the store holds for this artifact, in any order */
  def render(
      groupId: String,
      artifactId: String,
      versions: Iterable[String],
      lastUpdated: Instant = Instant.now()
  ): String =
    val ordered = MavenVersion.sorted(versions)
    val sb = StringBuilder()
    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    sb.append("<metadata>\n")
    sb.append(s"  <groupId>${escape(groupId)}</groupId>\n")
    sb.append(s"  <artifactId>${escape(artifactId)}</artifactId>\n")
    sb.append("  <versioning>\n")
    MavenVersion.latest(ordered).foreach(v => sb.append(s"    <latest>${escape(v)}</latest>\n"))
    MavenVersion.release(ordered).foreach(v => sb.append(s"    <release>${escape(v)}</release>\n"))
    sb.append("    <versions>\n")
    ordered.foreach(v => sb.append(s"      <version>${escape(v)}</version>\n"))
    sb.append("    </versions>\n")
    sb.append(s"    <lastUpdated>${LastUpdatedFormat.format(lastUpdated)}</lastUpdated>\n")
    sb.append("  </versioning>\n")
    sb.append("</metadata>\n")
    sb.toString

  private def escape(s: String): String =
    s.replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
