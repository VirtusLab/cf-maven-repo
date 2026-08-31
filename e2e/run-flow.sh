#!/usr/bin/env bash
# Round trip: build two libraries with two different build tools, publish both, then resolve them
# back from a cold cache.
#
#   usage: e2e/run-flow.sh [version] [extra publisher args...]
#
# Published versions are immutable, so re-running with the same version is expected to fail. Pass
# a fresh version, or --skip-existing to exercise only the resolve half.
set -euo pipefail

cd "$(dirname "$0")"
HERE="$PWD"
REPO="$(cd .. && pwd)"
STAGED="$HERE/staged"

VERSION="${1:-0.1.0}"; shift || true

# shellcheck source=target.sh
. "$HERE/target.sh"
: "${BUCKET:?set BUCKET to the bucket to publish into}"
: "${REPO_BASE:?set REPO_BASE to where readers fetch from, e.g. https://maven.example.com}"
# Build with: sbt cli/assembly
# Found by glob rather than by path: the Scala version is part of the output directory, and
# nothing here should have to be edited when it moves.
find_cli_jar() {
  ls -t "$REPO"/target/out/jvm/*/cf-maven-repo-cli/cf-maven-repo.jar 2>/dev/null | head -1
}
CLI_JAR="${CLI_JAR:-$(find_cli_jar)}"
REPO_URL="$REPO_BASE/releases"

PUBLISH_ARGS=()
if [ -n "$S3_ENDPOINT" ]; then
  # A bucket is a subdomain by default, which no host-and-port endpoint can serve.
  PUBLISH_ARGS+=(--endpoint "$S3_ENDPOINT" --path-style)
fi
if [ -n "$ZONE_ID" ]; then
  PUBLISH_ARGS+=(--public-url "$REPO_BASE" --cf-zone-id "$ZONE_ID")
fi

echo "==> publishing greeter-scalacli + greeter-sbt $VERSION, then resolving them from $REPO_URL"
echo

echo "--- 1/3  build and stage (scala-cli publish + sbt publish, same directory) ---"
rm -rf "$STAGED"; mkdir -p "$STAGED"
scala-cli --power publish lib-scala-cli -R "$STAGED" --signer none --project-version "$VERSION" --quiet
( cd lib-sbt && STAGE_DIR="$STAGED" FLOW_VERSION="$VERSION" sbt -batch publish >/dev/null )
echo "staged:"
find "$STAGED" -name '*.pom' | sed "s|$STAGED/|  |"

echo
echo "--- 2/3  upload (checksums, immutability, metadata from store state, purge) ---"
[ -f "$CLI_JAR" ] || { echo "publisher jar not found at $CLI_JAR (run: sbt cli/assembly)" >&2; exit 1; }
# Without --endpoint the SDK reads one from AWS_ENDPOINT_URL_S3.
java -jar "$CLI_JAR" publish --staging "$STAGED" --bucket "$BUCKET" \
  "${PUBLISH_ARGS[@]}" "$@" | tail -8

echo
echo "--- 3/3  resolve from a cold cache with scala-cli directives ---"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
sed -e "s|0\\.1\\.0|$VERSION|g" -e "s|^//> using repository .*|//> using repository $REPO_URL|" \
  consumer/consume.scala > "$WORK/consume.scala"
echo "consumer directives:"
grep '^//>' "$WORK/consume.scala" | sed 's/^/  /'
echo
export COURSIER_CACHE="$WORK/cs"
OUT="$(scala-cli run "$WORK/consume.scala" --quiet 2>&1)"
echo "$OUT" | sed 's/^/  /'

echo
if grep -q '\[scala-cli\] hello cf-maven-repo' <<<"$OUT" && grep -q '\[sbt\] hello cf-maven-repo' <<<"$OUT"; then
  printf '\033[32mPASS\033[0m  full round trip: scala-cli + sbt -> R2 -> scala-cli resolve\n'
else
  printf '\033[31mFAIL\033[0m  consumer did not print both greetings\n'; exit 1
fi
