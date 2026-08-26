#!/bin/bash
# Builds the OSGi bundle with plain javac against the jars that ship inside the
# installed redhat.java extension. No Maven, no Tycho, no target platform.
#
# The finished jar is written to out/ and copied into extension/server/, which is where
# package.json's contributes.javaExtensions points and therefore what vsce packages.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/out"
BUNDLE_ID="io.github.sorteam.bazel.jdtls"

# Single source of truth for the version: extension/package.json. Bundle-Version is substituted
# into the manifest at build time, so the two can never drift.
VERSION="$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
  "$HERE/extension/package.json" | head -1)"
if [ -z "$VERSION" ]; then
  echo "error: cannot read version from extension/package.json" >&2
  exit 1
fi

# JDK: JAVA_HOME, then whatever the platform knows about, then javac on PATH.
if [ -z "${JDK:-}" ]; then
  if [ -n "${JAVA_HOME:-}" ]; then
    JDK="$JAVA_HOME"
  elif [ -x /usr/libexec/java_home ]; then
    JDK="$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home 2>/dev/null || true)"
  fi
fi
if [ -n "${JDK:-}" ] && [ -x "$JDK/bin/javac" ]; then
  JAVAC="$JDK/bin/javac"
  JAR="$JDK/bin/jar"
elif command -v javac >/dev/null; then
  JAVAC="$(command -v javac)"
  JAR="$(command -v jar)"
else
  echo "error: no JDK found; set JAVA_HOME (17 or newer)" >&2
  exit 1
fi

# The bundle compiles against jdt.ls internals, so the classpath comes from an installed
# redhat.java. Which version that is decides which jdt.ls API the jar is built against - point
# REDHAT_JAVA at a specific one to build reproducibly.
REDHAT_JAVA="${REDHAT_JAVA:-$(ls -d "$HOME"/.vscode/extensions/redhat.java-*/server/plugins 2>/dev/null | sort -V | tail -1)}"

if [ ! -d "$REDHAT_JAVA" ]; then
  echo "error: cannot locate redhat.java server plugins; set REDHAT_JAVA" >&2
  exit 1
fi

CP="$(find "$REDHAT_JAVA" -name '*.jar' -print0 | tr '\0' ':')"

rm -rf "$OUT"
mkdir -p "$OUT/classes"

echo "==> version $VERSION"
echo "==> javac    $("$JAVAC" -version 2>&1)"
echo "==> jdt.ls   $REDHAT_JAVA"

find "$HERE/server/src" -name '*.java' > "$OUT/sources.txt"
"$JAVAC" \
  --release 17 \
  -nowarn \
  -classpath "$CP" \
  -d "$OUT/classes" \
  @"$OUT/sources.txt"

echo "==> packaging $BUNDLE_ID.jar"
cp "$HERE/server/plugin.xml" "$OUT/classes/plugin.xml"
sed "s/^Bundle-Version: .*/Bundle-Version: $VERSION/" \
  "$HERE/server/META-INF/MANIFEST.MF" > "$OUT/MANIFEST.MF"
"$JAR" --create \
  --file "$OUT/$BUNDLE_ID.jar" \
  --manifest "$OUT/MANIFEST.MF" \
  -C "$OUT/classes" .

# Without this the vsix ships a package.json pointing at a jar that is not in it, and the
# extension installs cleanly while importing nothing at all.
mkdir -p "$HERE/extension/server"
rm -f "$HERE/extension/server"/*.jar
cp "$OUT/$BUNDLE_ID.jar" "$HERE/extension/server/$BUNDLE_ID.jar"

echo "==> built $OUT/$BUNDLE_ID.jar"
echo "==> staged extension/server/$BUNDLE_ID.jar"
