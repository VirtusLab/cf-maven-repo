package org.virtuslab.mavenrepo

/** A Maven coordinate and the repository paths derived from it. */
final case class Coordinates(groupId: String, artifactId: String, version: String):

  def groupPath: String = groupId.replace('.', '/')

  def artifactPath: String = s"$groupPath/$artifactId"

  def versionPath: String = s"$artifactPath/$version"

  def fileName(suffix: String): String = s"$artifactId-$version$suffix"

  /** Presence of this key is what marks a version as published. */
  def pomPath: String = s"$versionPath/${fileName(".pom")}"

  override def toString: String = s"$groupId:$artifactId:$version"

object Coordinates:
  val MetadataFileName: String = "maven-metadata.xml"
