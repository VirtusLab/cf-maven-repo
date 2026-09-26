//> using scala 3.3.7
//> using repository https://replaced.by/run-flow
//> using dep org.virtuslab.cf-maven-repo::greeter-scalacli:0.1.0
//> using dep org.virtuslab.cf-maven-repo::greeter-sbt:0.1.0
//> using dep org.virtuslab.cf-maven-repo::greeter-mill:0.1.0

import org.virtuslab.cfmavenrepo.scalacli.Greeter
import org.virtuslab.cfmavenrepo.sbtlib.SbtGreeter
import org.virtuslab.cfmavenrepo.milllib.MillGreeter

@main def run(): Unit =
  println(Greeter.greet("cf-maven-repo"))
  println(SbtGreeter.greet("cf-maven-repo"))
  println(MillGreeter.greet("cf-maven-repo"))
