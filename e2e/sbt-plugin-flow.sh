#!/usr/bin/env bash
# The sbt plugin's scripted tests against a disposable Silo, with no account, credentials or network
# beyond what the test builds resolve.
#
#   usage: e2e/sbt-plugin-flow.sh [scripted test filter, e.g. sbt-cf-maven-repo/release]
#
# The scripted builds are separate sbt processes forked from this one, so they see the store only
# through the environment exported here. That is also why sbt runs as a fresh server rather than
# through a client: a server started earlier would not have this environment to hand down.
set -euo pipefail

cd "$(dirname "$0")"
REPO="$(cd .. && pwd)"

IMAGE="${MINIO_IMAGE:-pgsty/silo:RELEASE.2026-09-16T00-00-00Z}"
CONTAINER="cf-maven-sbt-plugin-flow"
PORT="${MINIO_PORT:-19002}"
BUCKET="cf-maven-sbt-plugin"
USER="miniouser"
PASS="miniopassword"
ENDPOINT="http://127.0.0.1:$PORT"

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

docker exec "$CONTAINER" mcli alias set local http://127.0.0.1:9000 "$USER" "$PASS" >/dev/null
docker exec "$CONTAINER" mcli mb --ignore-existing "local/$BUCKET" >/dev/null
docker exec "$CONTAINER" mcli anonymous set download "local/$BUCKET" >/dev/null
echo "    bucket $BUCKET is up and publicly readable"
echo

export AWS_ACCESS_KEY_ID="$USER"
export AWS_SECRET_ACCESS_KEY="$PASS"
export AWS_REGION=us-east-1
export CF_MAVEN_TEST_ENDPOINT="$ENDPOINT"
export CF_MAVEN_TEST_BUCKET="$BUCKET"

# The official sbt 2 script defaults to its thin client, which would hand the command to whatever
# server is already running; --server keeps sbt in this process. Other launchers run the server
# anyway and do not know the flag.
SBT=(sbt -batch)
if sbt --help 2>/dev/null | grep -q -- '--server'; then SBT+=(--server); fi

cd "$REPO"
"${SBT[@]}" "sbtPlugin/scripted ${1:-}"
