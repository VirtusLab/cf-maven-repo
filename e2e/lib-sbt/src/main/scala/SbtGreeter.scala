package org.virtuslab.cfmavenrepo.sbtlib

object SbtGreeter:
  def greet(who: String): String = s"[sbt] hello $who"
