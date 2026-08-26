#!/bin/bash
# Runs the plain-main test suite against the compiled bundle classes.
# No framework and no OSGi: only classes that stay clear of the Eclipse runtime are exercised.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/out"

if [ -z "${JDK:-}" ]; then
  if [ -n "${JAVA_HOME:-}" ]; then
    JDK="$JAVA_HOME"
  elif [ -x /usr/libexec/java_home ]; then
    JDK="$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home 2>/dev/null || true)"
  fi
fi
if [ -n "${JDK:-}" ] && [ -x "$JDK/bin/javac" ]; then
  JAVAC="$JDK/bin/javac"
  JAVA="$JDK/bin/java"
elif command -v javac >/dev/null; then
  JAVAC="$(command -v javac)"
  JAVA="$(command -v java)"
else
  echo "error: no JDK found; set JAVA_HOME (17 or newer)" >&2
  exit 1
fi

REDHAT_JAVA="${REDHAT_JAVA:-$(ls -d "$HOME"/.vscode/extensions/redhat.java-*/server/plugins 2>/dev/null | sort -V | tail -1)}"

if [ ! -d "$REDHAT_JAVA" ]; then
  echo "error: cannot locate redhat.java server plugins; set REDHAT_JAVA" >&2
  exit 1
fi

"$HERE/build.sh" >/dev/null

CP="$(find "$REDHAT_JAVA" -name '*.jar' -print0 | tr '\0' ':')$OUT/classes"

mkdir -p "$OUT/test-classes"
find "$HERE/server/test" -name '*.java' > "$OUT/test-sources.txt"
"$JAVAC" --release 17 -nowarn -classpath "$CP" -d "$OUT/test-classes" @"$OUT/test-sources.txt"

echo "==> running tests"
"$JAVA" -classpath "$CP:$OUT/test-classes" io.github.sorteam.bazel.jdtls.PluginTests
