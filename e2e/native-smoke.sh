#!/usr/bin/env bash
# Smoke-tests a native binary of the publisher against the failures a native image actually has.
#
#   usage: e2e/native-smoke.sh [--lite] <binary>
#
# A native image can lose what the JVM build takes for granted - URL protocol handlers, TLS, XML
# parsing, reflective service lookups - and every one of those failures still prints a correct
# --help. So this publishes for real.
#
# --lite drops the checks that need a container, for platforms with no Docker. What is left still
# reaches the store code: it asks the binary to talk to an address nothing answers on, and reads
# which way it fails.
set -euo pipefail

LITE=false
if [ "${1:-}" = "--lite" ]; then LITE=true; shift; fi
BIN="${1:?usage: e2e/native-smoke.sh [--lite] <binary>}"
case "$BIN" in /*) ;; *) BIN="$PWD/$BIN" ;; esac
[ -x "$BIN" ] || { echo "not executable: $BIN" >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"; docker rm -f "$CONTAINER" >/dev/null 2>&1 || true' EXIT
CONTAINER="cf-maven-native-smoke"
IMAGE="${MINIO_IMAGE:-pgsty/silo:RELEASE.2026-09-16T00-00-00Z}"
PORT="${MINIO_PORT:-19003}"

pass=0; fail=0
ok()  { printf '  \033[32mPASS\033[0m  %s\n' "$1"; pass=$((pass+1)); }
no()  { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; [ -n "${2:-}" ] && printf '        %s\n' "$2"; fail=$((fail+1)); }

# The smallest thing the publisher accepts: a POM that states the coordinates it is filed under,
# and one artifact beside it. No build tool needed, so this runs anywhere the binary does.
STAGING="$WORK/staging/org/example/smoke_3/1.0.0"
mkdir -p "$STAGING"
cat > "$STAGING/smoke_3-1.0.0.pom" <<'POM'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>org.example</groupId>
  <artifactId>smoke_3</artifactId>
  <version>1.0.0</version>
  <packaging>jar</packaging>
</project>
POM
printf 'not really a jar' > "$STAGING/smoke_3-1.0.0.jar"

echo "smoke-testing $BIN"
echo
echo "the binary itself"

OUT="$("$BIN" publish --help 2>&1)" && grep -q -- '--staging' <<<"$OUT" \
  && ok "publish --help" || no "publish --help" "$(head -2 <<<"$OUT")"

# The failure this is really looking for: without --enable-url-protocols the S3 client rejects
# its own endpoint as "not a valid URI" and never opens a socket. Refusing to connect is the
# right answer here; refusing to parse is not. The credentials are dummies, but they must be
# there: without them the SDK stops at its credentials chain and never reaches the endpoint.
for scheme in http https; do
  OUT="$(AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE AWS_SECRET_ACCESS_KEY=notarealsecretkey AWS_REGION=us-east-1 \
         "$BIN" publish --staging "$WORK/staging" --bucket smoke \
           --endpoint "$scheme://127.0.0.1:1" --path-style 2>&1 || true)"
  if grep -qi 'not a valid URI' <<<"$OUT"; then
    no "$scheme protocol handler" "$(grep -i 'not a valid URI' <<<"$OUT" | head -1)"
  elif grep -qiE 'refused|unable to execute|timed out|connect' <<<"$OUT"; then
    ok "$scheme reaches the network layer"
  else
    no "$scheme protocol handler" "$(tail -2 <<<"$OUT")"
  fi
done

# TLS, certificate store and the SDK's error parsing, against a server that answers: the request
# is unsigned garbage on purpose, so a 403 naming the access key is the success case.
OUT="$(AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE AWS_SECRET_ACCESS_KEY=notarealsecretkey AWS_REGION=us-east-1 \
       "$BIN" republish-metadata --bucket cf-maven-repo-smoke-does-not-exist --group org.example 2>&1 || true)"
if grep -qiE 'access key|403' <<<"$OUT"; then
  ok "https to a real endpoint, TLS and error parsing"
else
  no "https to a real endpoint" "$(tail -2 <<<"$OUT")"
fi

if [ "$LITE" = true ]; then
  echo
  printf 'result: %d passed, %d failed (lite: no container on this platform)\n' "$pass" "$fail"
  [ "$fail" -eq 0 ]
  exit
fi

echo
echo "against a real store"
docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
docker run -d --name "$CONTAINER" -p "$PORT:9000" \
  -e MINIO_ROOT_USER=smokeuser -e MINIO_ROOT_PASSWORD=smokepassword \
  "$IMAGE" server /data >/dev/null
for _ in $(seq 1 60); do
  curl -sS -o /dev/null "http://127.0.0.1:$PORT/minio/health/live" 2>/dev/null && break
  sleep 0.5
done
docker exec "$CONTAINER" mcli alias set smoke "http://127.0.0.1:9000" smokeuser smokepassword >/dev/null
docker exec "$CONTAINER" mcli mb --ignore-existing smoke/smoke >/dev/null
docker exec "$CONTAINER" mcli anonymous set download smoke/smoke >/dev/null

export AWS_ACCESS_KEY_ID=smokeuser AWS_SECRET_ACCESS_KEY=smokepassword AWS_REGION=us-east-1
STORE=(--bucket smoke --endpoint "http://127.0.0.1:$PORT" --path-style)

# Signing, conditional writes, the checksums, the POM parse and the metadata rewrite, all at once.
OUT="$("$BIN" publish --staging "$WORK/staging" "${STORE[@]}" 2>&1 || true)"
grep -q 'published org.example:smoke_3:1.0.0' <<<"$OUT" \
  && ok "publishes" || no "publishes" "$(tail -3 <<<"$OUT")"

BASE="http://127.0.0.1:$PORT/smoke/releases/org/example/smoke_3"
curl -fsS "$BASE/1.0.0/smoke_3-1.0.0.pom" >/dev/null 2>&1 \
  && ok "the POM is readable from the store" || no "the POM is readable from the store"
curl -fsS "$BASE/1.0.0/smoke_3-1.0.0.jar.sha1" >/dev/null 2>&1 \
  && ok "checksums were generated" || no "checksums were generated"
curl -fsS "$BASE/maven-metadata.xml" 2>/dev/null | grep -q '<version>1.0.0</version>' \
  && ok "maven-metadata.xml lists the version" || no "maven-metadata.xml lists the version"

OUT="$("$BIN" publish --staging "$WORK/staging" "${STORE[@]}" 2>&1 || true)"
grep -qi 'refusing to republish' <<<"$OUT" \
  && ok "re-publishing is refused" || no "re-publishing is refused" "$(tail -2 <<<"$OUT")"

echo
printf 'result: %d passed, %d failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
