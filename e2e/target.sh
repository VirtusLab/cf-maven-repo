# Normalises and checks the repository target. Sourced by the harness, never run.
#
# The target comes from the environment and nowhere else: no defaults, no config file, no lookup.
# Whoever runs the harness says where it points - CI from its secrets, a local live run from the
# shell, e2e/local-flow.sh from the container it just started. A harness that can find a real
# repository on its own is a harness that can publish to one by accident.
#
#   BUCKET       the object store bucket to publish into            (required)
#   REPO_BASE    where readers fetch from, e.g. https://maven.example.com
#                  - only the store's own host when a CDN fronts it (required)
#   ZONE_ID      Cloudflare zone owning REPO_BASE; unset means publish without purging
#   S3_ENDPOINT  for an S3-compatible store that is not R2

: "${BUCKET:=}"
: "${REPO_BASE:=}"
: "${ZONE_ID:=}"
: "${S3_ENDPOINT:=}"

REPO_BASE="${REPO_BASE%/}"
