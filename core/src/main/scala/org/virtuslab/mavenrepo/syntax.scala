package org.virtuslab.mavenrepo

/** Discards a value on purpose.
  *
  * `-Wvalue-discard` and `-Wnonunit-statement` are both on, so every ignored result has to say so. A `: Unit` ascription does that, but it
  * reads as a type annotation and says nothing about intent. This names what is happening.
  *
  * Internal to the library: it is deliberately not part of what a caller gets from `import org.virtuslab.mavenrepo.*`.
  */
extension (a: Any) private[mavenrepo] inline def discard: Unit = ()
