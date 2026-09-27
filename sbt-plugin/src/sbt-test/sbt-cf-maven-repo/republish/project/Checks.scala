import org.virtuslab.mavenrepo.S3ObjectStore
import sbt.*
import sbt.Keys.*
import sbt.ProjectExtra.extract

import scala.io.Source

/** Assertions against the bucket, as commands so no task cache can skip them. The store is the plugin's own: core is on this build's
  * classpath because the plugin is.
  */
object Checks:

  private def env(name: String): String =
    sys.env.getOrElse(name, sys.error(s"$name is not set: run this through e2e/sbt-plugin-flow.sh"))

  def endpoint: String = env("CF_MAVEN_TEST_ENDPOINT")
  def bucket: String = env("CF_MAVEN_TEST_BUCKET")

  private def withStore[A](use: S3ObjectStore => A): A =
    val store = S3ObjectStore(bucket, Some(endpoint), None, pathStyle = true)
    try use(store)
    finally store.close()

  val commands: Seq[Command] = Seq(
    Command.args("checkPresent", "<key>") { (s, keys) =>
      withStore(store => keys.foreach(k => if store.head(k).isEmpty then sys.error(s"$k is not in the bucket")))
      s
    },
    Command.args("checkAbsent", "<key>") { (s, keys) =>
      withStore(store => keys.foreach(k => if store.head(k).isDefined then sys.error(s"$k is in the bucket")))
      s
    },
    Command.args("checkMetadataLists", "<metadata key> <version>") { (s, args) =>
      val Seq(key, version) = args: @unchecked
      val xml = withStore(
        _.read(key).map(in =>
          try Source.fromInputStream(in, "UTF-8").mkString
          finally in.close()
        )
      )
      xml match
        case None                                                         => sys.error(s"$key is not in the bucket")
        case Some(body) if !body.contains(s"<version>$version</version>") => sys.error(s"$key does not list $version:\n$body")
        case Some(_)                                                      => s
    },
    Command.args("deleteKeys", "<key>") { (s, keys) =>
      withStore(store => keys.foreach(store.delete))
      s
    },
    // Every project's publishTo, by resolver name: a release must leave the build's own in place.
    Command.single("checkPublishTo") { (s, expected) =>
      val x = Project.extract(s)
      x.structure.allProjectRefs.foldLeft(s) { (s, ref) =>
        val (next, resolver) = x.runTask(ref / publishTo, s)
        val found = resolver.map(_.name)
        if !found.contains(expected) then sys.error(s"${ref.project} / publishTo is $found, expected $expected")
        next
      }
    }
  )
