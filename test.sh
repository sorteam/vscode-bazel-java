#!/bin/bash
# Runs the plain-main test suite against the compiled bundle classes.
# No framework and no OSGi: only classes that stay clear of the Eclipse runtime are exercised.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/out"

JDK="${JDK:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}"
REDHAT_JAVA="${REDHAT_JAVA:-$(ls -d "$HOME"/.vscode/extensions/redhat.java-*/server/plugins 2>/dev/null | sort -V | tail -1)}"

if [ ! -d "$REDHAT_JAVA" ]; then
  echo "error: cannot locate redhat.java server plugins; set REDHAT_JAVA" >&2
  exit 1
fi

"$HERE/build.sh" >/dev/null

CP="$(find "$REDHAT_JAVA" -name '*.jar' -print0 | tr '\0' ':')$OUT/classes"

mkdir -p "$OUT/test-classes"
find "$HERE/server/test" -name '*.java' > "$OUT/test-sources.txt"
"$JDK/bin/javac" --release 17 -nowarn -classpath "$CP" -d "$OUT/test-classes" @"$OUT/test-sources.txt"

echo "==> running tests"
"$JDK/bin/java" -classpath "$CP:$OUT/test-classes" ch.audienzz.bazel.jdtls.PluginTests
