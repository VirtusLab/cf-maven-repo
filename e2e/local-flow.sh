#!/usr/bin/env bash
# The whole flow against a disposable MinIO, with no account, credentials or network.
#
#   usage: e2e/local-flow.sh [version]
#
# A Maven repository is a naming convention over HTTP GETs, so everything except the CDN itself
# can be checked against any store that serves objects publicly. That is the resolver matrix,
# the generated checksums, metadata and version ranges, immutability, and the round trip through
# two build tools. Only edge caching and purging need Cloudflare.
set -euo pipefail

cd "$(dirname "$0")"
HERE="$PWD"
REPO="$(cd .. && pwd)"

VERSION="${1:-0.1.0}"
# Silo, the maintained fork of the MinIO server; MinIO itself no longer publishes images.
IMAGE="${MINIO_IMAGE:-pgsty/silo:RELEASE.2026-09-16T00-00-00Z}"
CONTAINER="cf-maven-local-flow"
PORT="${MINIO_PORT:-19000}"
BUCKET="cf-maven-local"
USER="miniouser"
PASS="miniopassword"
ENDPOINT="http://127.0.0.1:$PORT"
# Found by glob rather than by path: the Scala version is part of the output directory, and
# nothing here should have to be edited when it moves.
find_cli_jar() {
  ls -t "$REPO"/target/out/jvm/*/cf-maven-repo-cli/cf-maven-repo.jar 2>/dev/null | head -1
}
CLI_JAR="${CLI_JAR:-$(find_cli_jar)}"

[ -f "$CLI_JAR" ] || { echo "publisher jar not found at $CLI_JAR (run: sbt cli/assembly)" >&2; exit 1; }
command -v docker >/dev/null || { echo "docker is required" >&2; exit 1; }

cleanup() { docker rm -f "$CONTAINER" >/dev/null 2>&1 || true; }
trap cleanup EXIT
cleanup

echo "==> starting $IMAGE on $ENDPOINT"
docker run -d --name "$CONTAINER" -p "$PORT:9000" \
  -e "MINIO_ROOT_USER=$USER" -e "MINIO_ROOT_PASSWORD=$PASS" \
  "$IMAGE" server /data >/dev/null

for _ in $(seq 1 60); do
  curl -sS -o /dev/null "$ENDPOINT/minio/health/live" 2>/dev/null && break
  sleep 0.5
done

# The client ships inside the image as mcli, so the bucket and its public read policy need nothing
# on the host. Anonymous download is what makes the bucket a repository: resolvers carry no
# credentials.
docker exec "$CONTAINER" mcli alias set local http://127.0.0.1:9000 "$USER" "$PASS" >/dev/null
docker exec "$CONTAINER" mcli mb --ignore-existing "local/$BUCKET" >/dev/null
docker exec "$CONTAINER" mcli anonymous set download "local/$BUCKET" >/dev/null
echo "    bucket $BUCKET is up and publicly readable"
echo

export AWS_ACCESS_KEY_ID="$USER"
export AWS_SECRET_ACCESS_KEY="$PASS"
export AWS_REGION=us-east-1
export BUCKET
export S3_ENDPOINT="$ENDPOINT"
export REPO_BASE="$ENDPOINT/$BUCKET"
export ZONE_ID=""      # no CDN, so nothing to purge
export CLI_JAR

# The fixture set the acceptance checklist reads: three versions, so version ranges and
# maven-metadata.xml are exercised rather than just an exact fetch.
"$HERE/stage-test-artifacts.sh" >/dev/null
java -jar "$CLI_JAR" publish --staging "$HERE/staging" --bucket "$BUCKET" \
  --endpoint "$ENDPOINT" --path-style | tail -3
echo

"$HERE/run-flow.sh" "$VERSION"
echo

"$HERE/verify.sh" "$REPO_BASE/releases"
