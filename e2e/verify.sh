#!/usr/bin/env bash
# Checks that a published repository is usable by every resolver.
#
#   usage: e2e/verify.sh [repository-url]
#
# Every resolver test uses a throwaway cache directory; otherwise it would be testing the local
# coursier cache rather than the repository.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=target.sh
. "$HERE/target.sh"

REPO_URL="${1:-${REPO_BASE:+$REPO_BASE/releases}}"
: "${REPO_URL:?pass a repository url, or set REPO_BASE}"

# The fixtures e2e/stage-test-artifacts.sh publishes.
GROUP="${FIXTURE_GROUP:-org.virtuslab.cf-maven-repo}"
ARTIFACT="${FIXTURE_ARTIFACT:-hello_3}"
PACKAGE="${FIXTURE_PACKAGE:-org.virtuslab.cfmavenrepo}"
VERSION="${FIXTURE_VERSION:-0.2.0}"
GROUP_PATH="${GROUP//.//}"

JAR_URL="$REPO_URL/$GROUP_PATH/$ARTIFACT/$VERSION/$ARTIFACT-$VERSION.jar"
POM_URL="$REPO_URL/$GROUP_PATH/$ARTIFACT/$VERSION/$ARTIFACT-$VERSION.pom"
META_URL="$REPO_URL/$GROUP_PATH/$ARTIFACT/maven-metadata.xml"

REPO="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=publisher.sh
. "$REPO/e2e/publisher.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
export COURSIER_CACHE="$WORK/coursier"
export CS_CACHE="$WORK/coursier"

pass=0; fail=0; skip=0
ok()   { printf '  \033[32mPASS\033[0m  %s\n' "$1"; pass=$((pass+1)); }
no()   { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; [ $# -gt 1 ] && printf '        %s\n' "$2"; fail=$((fail+1)); }
skipit(){ printf '  \033[33mSKIP\033[0m  %s\n' "$1"; [ $# -gt 1 ] && printf '        %s\n' "$2"; skip=$((skip+1)); }

echo "verifying $REPO_URL"
echo

# --- cache headers -------------------------------------------------------------------------
echo "cache headers"
JAR_HDRS="$(curl -sSI "$JAR_URL" 2>&1)"
if grep -qiE '^HTTP/[0-9.]+ 200' <<<"$JAR_HDRS"; then
  CC="$(grep -i '^cache-control:' <<<"$JAR_HDRS" | tr -d '\r')"
  if grep -qi 'immutable' <<<"$CC"; then ok "jar is immutable  ($CC)"; else no "jar cache-control" "$CC"; fi
  CT="$(grep -i '^content-type:' <<<"$JAR_HDRS" | tr -d '\r')"
  grep -qi 'java-archive' <<<"$CT" && ok "jar content-type  ($CT)" || no "jar content-type" "$CT"
else
  no "jar is reachable" "$(head -1 <<<"$JAR_HDRS")"
fi

META_HDRS="$(curl -sSI "$META_URL" 2>&1)"
if grep -qiE '^HTTP/[0-9.]+ 200' <<<"$META_HDRS"; then
  CC="$(grep -i '^cache-control:' <<<"$META_HDRS" | tr -d '\r')"
  if grep -qiE 'max-age=([0-9]|[1-9][0-9]|[1-9][0-9]{2})\b' <<<"$CC" && ! grep -qi immutable <<<"$CC"; then
    ok "maven-metadata.xml is short-lived  ($CC)"
  else
    no "maven-metadata.xml cache-control" "$CC"
  fi
else
  no "maven-metadata.xml is reachable" "$(head -1 <<<"$META_HDRS")"
fi

# Only a CDN can answer for its own cache; a bare object store has no edge to ask about.
BEHIND_CDN=false
grep -qi '^cf-cache-status:' <<<"$JAR_HDRS" && BEHIND_CDN=true

# --- edge cache ----------------------------------------------------------------------------
# Checked on a GET rather than a HEAD: resolvers issue GETs, and a HEAD can report DYNAMIC for an
# object a GET reports as HIT.
if [ "$BEHIND_CDN" = true ]; then
  curl -sS -o /dev/null "$JAR_URL"
  STATUS="$(curl -sS -o /dev/null -D - "$JAR_URL" | tr -d '\r' | grep -i '^cf-cache-status:')"
  case "$STATUS" in
    *HIT*) ok "edge cache  ($STATUS)" ;;
    *)     no "edge cache" "$STATUS (expected HIT on the second fetch)" ;;
  esac

  # A missing artifact must not be held: a resolver that probes before a release would keep
  # seeing the 404 for as long as it was cached.
  MISSING="$REPO_URL/$GROUP_PATH/$ARTIFACT/99.99.99/$ARTIFACT-99.99.99.pom"
  curl -sS -o /dev/null "$MISSING"
  NEG="$(curl -sS -o /dev/null -D - "$MISSING" | tr -d '\r' | grep -i '^cf-cache-status:')"
  case "$NEG" in
    *HIT*) no "404s are not cached" "$NEG - a published version would stay invisible" ;;
    *)     ok "404s are not cached  ($NEG)" ;;
  esac
else
  skipit "edge cache" "no CDN in front of $REPO_URL"
  skipit "404s are not cached" "no CDN in front of $REPO_URL"
fi

# --- resolvers ------------------------------------------------------------------------------
echo
echo "resolvers"

if command -v scala-cli >/dev/null; then
  mkdir -p "$WORK/scli"
  cat > "$WORK/scli/main.scala" <<SCALA
//> using scala 3.3.7
//> using repository $REPO_URL
//> using dep $GROUP::${ARTIFACT%_3}:$VERSION
@main def run(): Unit = println($PACKAGE.Hello.greeting)
SCALA
  OUT="$(cd "$WORK/scli" && scala-cli run . --quiet 2>&1)"
  if grep -q "cf-maven-repo, version $VERSION" <<<"$OUT"; then
    ok "scala-cli resolves and the class loads"
  else
    no "scala-cli" "$(tail -3 <<<"$OUT")"
  fi
else
  skipit "scala-cli" "not on PATH"
fi

# coursier: exact version, then a range, which is what reads maven-metadata.xml
if command -v cs >/dev/null; then
  if OUT="$(cs fetch -r "$REPO_URL" "$GROUP:$ARTIFACT:$VERSION" 2>&1)"; then
    ok "coursier resolves an exact version"
  else
    no "coursier exact version" "$(tail -3 <<<"$OUT")"
  fi
  if OUT="$(cs fetch -r "$REPO_URL" "$GROUP:$ARTIFACT:[0.+,999999.+)" 2>&1)"; then
    ok "coursier resolves a version range (maven-metadata.xml is being read)"
  else
    no "coursier version range" "$(tail -3 <<<"$OUT")"
  fi
else
  skipit "coursier" "not on PATH"
fi

# sbt
if command -v sbt >/dev/null; then
  mkdir -p "$WORK/sbt/project"
  echo 'sbt.version=1.10.7' > "$WORK/sbt/project/build.properties"
  cat > "$WORK/sbt/build.sbt" <<SBT
ThisBuild / scalaVersion := "3.3.7"
resolvers += "cf-maven-repo" at "$REPO_URL"
libraryDependencies += "$GROUP" %% "${ARTIFACT%_3}" % "$VERSION"
SBT
  # Judged by the exit code, not by grepping for [success]: with CI set, as it is on GitHub
  # Actions, sbt colours its output even into a pipe, and the literal never appears. Colour is
  # still turned off so the [error] lines, which name the module and URL that failed, can be found.
  if OUT="$(cd "$WORK/sbt" && sbt -batch -Dsbt.color=false -Dsbt.server.forcestart=true update 2>&1)"; then
    ok "sbt resolves"
  else
    no "sbt" "$(grep -F '[error]' <<<"$OUT" | head -6)"
  fi
else
  skipit "sbt" "not on PATH"
fi

# gradle
# Gradle rejects a plain-http repository outright, which a local object store is.
GRADLE_INSECURE=""
case "$REPO_URL" in http://*) GRADLE_INSECURE="; isAllowInsecureProtocol = true" ;; esac
if command -v gradle >/dev/null; then
  mkdir -p "$WORK/gradle"
  cat > "$WORK/gradle/build.gradle.kts" <<GRADLE
plugins { id("java") }
// mavenCentral() is required: the test POM depends on scala3-library_3, which lives there.
// hello_3 exists only in our repo, so this still proves the repo is being used.
repositories { mavenCentral(); maven { url = uri("$REPO_URL")$GRADLE_INSECURE } }
dependencies { implementation("$GROUP:$ARTIFACT:$VERSION") }
tasks.register("resolveIt") { doLast { configurations.getByName("compileClasspath").files.forEach { println(it.name) } } }
GRADLE
  echo 'rootProject.name = "verify"' > "$WORK/gradle/settings.gradle.kts"
  OUT="$(cd "$WORK/gradle" && gradle --no-daemon --quiet --project-cache-dir "$WORK/gradle-cache" -g "$WORK/gradle-home" resolveIt 2>&1)"
  if grep -q "$ARTIFACT-$VERSION.jar" <<<"$OUT"; then ok "gradle resolves"; else no "gradle" "$(tail -5 <<<"$OUT")"; fi
else
  skipit "gradle" "not on PATH"
fi

# mill
MILL=""
command -v mill >/dev/null && MILL="mill"
if [ -n "$MILL" ]; then
  mkdir -p "$WORK/mill"
  # Pinned in the header, which any Mill launcher honours: the build below is Mill 1 syntax.
  cat > "$WORK/mill/build.mill" <<MILL_BUILD
//| mill-version: 1.1.9
package build
import mill.*, mill.scalalib.*

object verify extends ScalaModule {
  def scalaVersion = "3.3.7"
  def repositories = Task { super.repositories() :+ "$REPO_URL" }
  def mvnDeps = Seq(mvn"$GROUP::${ARTIFACT%_3}:$VERSION")
}
MILL_BUILD
  # show, not a bare target: mill only prints a task's result when asked to.
  OUT="$(cd "$WORK/mill" && $MILL show verify.resolvedMvnDeps 2>&1)"
  if grep -q "$ARTIFACT-$VERSION.jar" <<<"$OUT"; then ok "mill resolves"; else no "mill" "$(tail -5 <<<"$OUT")"; fi
else
  skipit "mill" "not on PATH - install the launcher from https://mill-build.org (coursier's mill is 0.11)"
fi

# --- immutability --------------------------------------------------------------------------
# The store is not always the host the repository is read from, so it is addressed separately.
STORE_ARGS=()
[ -n "${S3_ENDPOINT:-}" ] && STORE_ARGS+=(--endpoint "$S3_ENDPOINT" --path-style)
echo
echo "immutability"
if [ -d "$(dirname "$0")/staging" ] && [ -n "${BUCKET:-}" ]; then
  OUT="$("${PUBLISHER[@]}" publish --bucket "$BUCKET" "${STORE_ARGS[@]}" \
          --staging "$(dirname "$0")/staging" 2>&1)"
  if grep -qi "refusing to republish" <<<"$OUT"; then
    ok "re-publishing an existing version is refused"
  else
    no "re-publish protection" "expected a refusal, got: $(tail -3 <<<"$OUT")"
  fi
else
  skipit "re-publish protection" "needs BUCKET=<bucket> and e2e/stage-test-artifacts.sh"
fi

echo
printf 'result: %d passed, %d failed, %d skipped\n' "$pass" "$fail" "$skip"
[ "$fail" -eq 0 ]
