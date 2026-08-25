#!/bin/bash
# Builds the OSGi bundle with plain javac against the jars that ship inside the
# installed redhat.java extension. No Maven, no Tycho, no target platform.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/out"
BUNDLE_ID="ch.audienzz.bazel.jdtls"

JDK="${JDK:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}"
REDHAT_JAVA="${REDHAT_JAVA:-$(ls -d "$HOME"/.vscode/extensions/redhat.java-*/server/plugins 2>/dev/null | sort -V | tail -1)}"

if [ ! -d "$REDHAT_JAVA" ]; then
  echo "error: cannot locate redhat.java server plugins; set REDHAT_JAVA" >&2
  exit 1
fi

CP="$(find "$REDHAT_JAVA" -name '*.jar' -print0 | tr '\0' ':')"

rm -rf "$OUT"
mkdir -p "$OUT/classes"

echo "==> compiling against $REDHAT_JAVA"
find "$HERE/server/src" -name '*.java' > "$OUT/sources.txt"
"$JDK/bin/javac" \
  --release 17 \
  -nowarn \
  -classpath "$CP" \
  -d "$OUT/classes" \
  @"$OUT/sources.txt"

echo "==> packaging $BUNDLE_ID.jar"
cp "$HERE/server/plugin.xml" "$OUT/classes/plugin.xml"
"$JDK/bin/jar" --create \
  --file "$OUT/$BUNDLE_ID.jar" \
  --manifest "$HERE/server/META-INF/MANIFEST.MF" \
  -C "$OUT/classes" .

echo "==> built $OUT/$BUNDLE_ID.jar"
