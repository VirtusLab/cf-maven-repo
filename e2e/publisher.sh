# Resolves how the publisher is invoked. Sourced by the harness, never run.
#
#   CLI_BIN  a native binary, run directly; takes precedence when set
#   CLI_JAR  the assembly jar, run with `java -jar`; found by glob when unset
#
# Both builds take the same arguments, so the same harness checks either one. That is the point:
# a native binary that fails only against a real store — as one missing its URL protocol handlers
# does — passes `--help` and would otherwise ship.
#
# Found by glob rather than by path: the Scala version is part of the output directory, and
# nothing here should have to be edited when it moves.

PUBLISHER_REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [ -n "${CLI_BIN:-}" ]; then
  [ -x "$CLI_BIN" ] || { echo "publisher binary not executable: $CLI_BIN" >&2; exit 1; }
  PUBLISHER=("$CLI_BIN")
  PUBLISHER_DESC="$CLI_BIN"
else
  CLI_JAR="${CLI_JAR:-$(ls -t "$PUBLISHER_REPO"/target/out/jvm/*/cf-maven-repo-cli/cf-maven-repo.jar 2>/dev/null | head -1)}"
  [ -f "${CLI_JAR:-}" ] || {
    echo "publisher not found: set CLI_BIN to a native binary, or build the jar with 'sbt cli/assembly'" >&2
    exit 1
  }
  PUBLISHER=(java -jar "$CLI_JAR")
  PUBLISHER_DESC="$CLI_JAR"
fi
