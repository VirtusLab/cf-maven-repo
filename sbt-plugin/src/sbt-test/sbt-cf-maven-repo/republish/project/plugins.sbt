sys.props.get("plugin.version") match
  case Some(v) => addSbtPlugin("org.virtuslab" % "sbt-cf-maven-repo" % v)
  case None    => sys.error("plugin.version is not set: run this through scripted")
