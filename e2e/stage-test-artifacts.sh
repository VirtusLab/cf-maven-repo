#!/usr/bin/env bash
# Stages a small Scala 3 library into staging/ in Maven layout.
#
# Three versions, so that maven-metadata.xml and version-range resolution can be tested rather
# than just an exact-version fetch. One of them carries an unrecognised qualifier, which Maven
# orders above the bare release.
set -euo pipefail

cd "$(dirname "$0")"

# Kept in step with e2e/verify.sh, which reads the same knobs.
GROUP_ID="${FIXTURE_GROUP:-org.virtuslab.cf-maven-repo}"
GROUP_PATH="${GROUP_ID//.//}"
ARTIFACT="${FIXTURE_ARTIFACT:-hello_3}"
# A groupId may contain a hyphen; a Scala package may not, so the two differ here on purpose.
PACKAGE="${FIXTURE_PACKAGE:-org.virtuslab.cfmavenrepo}"
SCALA_VERSION="3.3.7"
VERSIONS=("0.1.0" "0.2.0" "1.39.0-core.0.6")

STAGING="staging"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

rm -rf "$STAGING"

for VERSION in "${VERSIONS[@]}"; do
  DEST="$STAGING/$GROUP_PATH/$ARTIFACT/$VERSION"
  mkdir -p "$DEST"
  SRC="$WORK/$VERSION"
  mkdir -p "$SRC"

  cat > "$SRC/Hello.scala" <<SCALA
package $PACKAGE

/** Proof that a bucket with the right object keys is a Maven repository. */
object Hello:
  val version: String = "$VERSION"
  def greeting: String = s"hello from cf-maven-repo, version \$version"
SCALA

  echo "  building $ARTIFACT-$VERSION.jar"
  scala-cli --power package "$SRC/Hello.scala" \
    --library --scala "$SCALA_VERSION" \
    -o "$DEST/$ARTIFACT-$VERSION.jar" --force --quiet

  (cd "$SRC" && jar cf "$OLDPWD/$DEST/$ARTIFACT-$VERSION-sources.jar" Hello.scala)

  cat > "$DEST/$ARTIFACT-$VERSION.pom" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>$GROUP_ID</groupId>
  <artifactId>$ARTIFACT</artifactId>
  <version>$VERSION</version>
  <packaging>jar</packaging>
  <name>cf-maven-repo test fixture</name>
  <description>Test artifact for the cf-maven-repo end-to-end harness.</description>
  <dependencies>
    <dependency>
      <groupId>org.scala-lang</groupId>
      <artifactId>scala3-library_3</artifactId>
      <version>$SCALA_VERSION</version>
    </dependency>
  </dependencies>
</project>
POM
done

echo
echo "staged under $STAGING:"
find "$STAGING" -type f | sort | sed 's/^/  /'
