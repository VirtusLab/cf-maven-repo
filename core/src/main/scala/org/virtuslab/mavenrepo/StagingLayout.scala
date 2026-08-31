package org.virtuslab.mavenrepo

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using

final case class VersionDir(directory: Path, coordinates: Coordinates, files: Seq[Path]):

  def artifacts: Seq[Path] = files.filterNot(f => Checksums.isChecksum(f.getFileName.toString))

  def checksums: Seq[Path] = files.filter(f => Checksums.isChecksum(f.getFileName.toString))

/** Reads a Maven-layout directory tree, as emitted by `scala-cli publish -R` or sbt's `publishTo` pointed at a directory.
  */
object StagingLayout:

  /** A directory that holds a POM but does not sit at a coordinate-shaped path. */
  final case class Invalid(directory: Path, reason: String)

  /** Any directory containing a `.pom` is a version directory; its path relative to the root yields the coordinates.
    *
    * A malformed tree is a `Left` rather than an exception: the staging directory is user input, and a caller has to be able to name the
    * directory that was wrong. Walking the tree can still fail with an `IOException`, the way any file access can.
    */
  def discover(root: Path): Either[Invalid, Seq[VersionDir]] =
    if !Files.isDirectory(root) then Right(Seq.empty)
    else
      val dirs = Using.resource(Files.walk(root)) { stream =>
        stream
          .iterator()
          .asScala
          .filter(p => Files.isRegularFile(p) && p.getFileName.toString.endsWith(".pom"))
          .map(_.getParent)
          .distinct
          .toVector
          .sorted
      }
      dirs.foldLeft(Right(Vector.empty): Either[Invalid, Vector[VersionDir]]) { (acc, dir) =>
        acc.flatMap(found => versionDir(root, dir).map(found :+ _))
      }

  private def versionDir(root: Path, dir: Path): Either[Invalid, VersionDir] =
    val parts = root.relativize(dir).iterator().asScala.map(_.toString).toVector
    if parts.length < 3 then Left(Invalid(dir, s"it is not <group as path>/<artifact>/<version> relative to $root"))
    else
      val coordinates = Coordinates(
        groupId = parts.dropRight(2).mkString("."),
        artifactId = parts(parts.length - 2),
        version = parts.last
      )
      val files = Using.resource(Files.list(dir)) { s =>
        s.iterator().asScala.filter(Files.isRegularFile(_)).toVector.sorted
      }
      Right(VersionDir(dir, coordinates, files))
