package org.virtuslab.mavenrepo

import org.w3c.dom.Element
import org.xml.sax.{ErrorHandler, SAXParseException}

import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import scala.util.Try

/** The coordinates a POM declares about itself.
  *
  * Maven derives an artifact's repository path from these, so a POM that disagrees with the path it is published to resolves as something
  * other than what it claims to be - permanently, on a store where nothing is rewritten.
  */
object PomCoordinates:

  enum Coordinate:
    case Literal(value: String)

    /** Written as a property, which resolves against a model built from the whole reactor rather than from this file. Maven's own tooling
      * flattens these before deployment.
      */
    case Unresolved(raw: String)

    /** Neither declared nor inherited from a `<parent>`. */
    case Absent

  final case class Declared(groupId: Coordinate, artifactId: Coordinate, version: Coordinate)

  /** `Left` with the parser's own account of the problem when the file is not readable as XML. The reason is carried rather than dropped so
    * the caller can say what is wrong with the file instead of only that something is.
    *
    * `groupId` and `version` fall back to the `<parent>`, which is where a module in a multi-module build carries them. One level is enough
    * for all of them: Maven requires a `<parent>` to name its own groupId, artifactId and version literally, so a POM that inherits a
    * coordinate always states it in this same file.
    */
  def read(pom: Path): Either[String, Declared] =
    Try {
      val factory = DocumentBuilderFactory.newInstance()
      // These files come from outside the publisher, and no POM has any use for a DOCTYPE.
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
      factory.setXIncludeAware(false)
      factory.setExpandEntityReferences(false)
      val builder = factory.newDocumentBuilder()
      // The default handler prints to stderr and then throws. The exception is the only channel
      // wanted here, so a rejected POM does not look like a crash in an otherwise green run.
      builder.setErrorHandler(new ErrorHandler:
        def warning(e: SAXParseException): Unit = ()
        def error(e: SAXParseException): Unit = throw e
        def fatalError(e: SAXParseException): Unit = throw e)
      val root = builder.parse(pom.toFile).getDocumentElement
      val parent = child(root, "parent")

      def inherited(name: String): Coordinate =
        coordinate(root, name) match
          case Coordinate.Absent => parent.map(coordinate(_, name)).getOrElse(Coordinate.Absent)
          case declared          => declared

      Declared(
        groupId = inherited("groupId"),
        artifactId = coordinate(root, "artifactId"),
        version = inherited("version")
      )
    }.toEither.left.map(explain)

  /** A parse failure names the line and column; anything else has only its message, and some have none at all. */
  private def explain(failure: Throwable): String = failure match
    case e: SAXParseException => s"${e.getMessage} (line ${e.getLineNumber}, column ${e.getColumnNumber})"
    case e                    => Option(e.getMessage).getOrElse(e.getClass.getName)

  private def coordinate(element: Element, name: String): Coordinate =
    child(element, name).flatMap(e => Option(e.getTextContent)).map(_.trim) match
      case None                                => Coordinate.Absent
      case Some(value) if value.isEmpty        => Coordinate.Absent
      case Some(value) if value.contains("${") => Coordinate.Unresolved(value)
      case Some(value)                         => Coordinate.Literal(value)

  /** Direct children only: a recursive lookup would find the `<version>` of a dependency. */
  private def child(element: Element, name: String): Option[Element] =
    val children = element.getChildNodes
    (0 until children.getLength).iterator
      .map(children.item)
      .collect { case e: Element if e.getNodeName == name => e }
      .nextOption()
