package org.virtuslab.mavenrepo

import java.nio.file.{Files, Path, StandardOpenOption}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Records keys a publish run has uploaded, so a resumed run can skip them without asking the store about each one.
  *
  * Only an optimisation: the store remains the source of truth, and a missing or stale ledger costs nothing but a slower resume.
  * Append-only, so a killed process leaves a usable file.
  */
final class Ledger private (path: Option[Path], alreadyDone: Set[String]):

  def contains(key: String): Boolean = alreadyDone.contains(key)

  def record(key: String): Unit =
    path.foreach { p =>
      Files.writeString(p, key + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND).discard
    }

object Ledger:

  private val ScopeMarker = "# scope "

  /** @param scope
    *   identifies the destination this ledger describes, e.g. `bucket/prefix`.
    *
    * A ledger records keys, and the same key means different objects in different destinations. A file written for another destination is
    * therefore discarded rather than believed: trusting it would report a successful publish having uploaded nothing.
    */
  def open(path: Path, scope: String): Ledger =
    Option(path.getParent).foreach(Files.createDirectories(_): Unit)
    val marker = ScopeMarker + scope
    val lines =
      if Files.exists(path) then Try(Files.readAllLines(path).asScala.toVector).getOrElse(Seq.empty)
      else Seq.empty

    val usable = lines.headOption.contains(marker)
    if !usable then
      Files.writeString(path, marker + "\n").discard
      new Ledger(Some(path), Set.empty)
    else new Ledger(Some(path), lines.tail.filter(_.nonEmpty).toSet)

  /** Records nothing and remembers nothing. */
  val disabled: Ledger = new Ledger(None, Set.empty)
