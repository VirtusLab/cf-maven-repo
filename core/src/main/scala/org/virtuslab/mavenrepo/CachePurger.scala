package org.virtuslab.mavenrepo

/** Invalidates cached copies of URLs a publish run made stale.
  *
  * Only metadata is ever purged; artifacts are immutable, so a cached copy cannot be wrong. Failing to purge leaves the repository correct
  * but up to one metadata TTL behind.
  *
  * Publishing without a CDN in front is the ordinary case, and it is expressed by having no purger at all - see [[PublishTarget.purger]] -
  * rather than by an implementation that accepts URLs and does nothing with them.
  */
trait CachePurger:
  def purge(urls: Seq[String]): Either[String, Unit]
