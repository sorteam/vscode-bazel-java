#!/bin/bash
# Builds the bundle and installs the extension into ~/.vscode/extensions.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
BUNDLE_ID="ch.audienzz.bazel.jdtls"
TARGET="$HOME/.vscode/extensions/audienzz.bazel-java-0.1.0"

"$HERE/build.sh"

echo "==> installing into $TARGET"
rm -rf "$TARGET"
mkdir -p "$TARGET/server"
cp "$HERE/extension/package.json" "$TARGET/package.json"
cp "$HERE/extension/extension.js" "$TARGET/extension.js"
cp "$HERE/out/$BUNDLE_ID.jar" "$TARGET/server/$BUNDLE_ID.jar"

echo "==> installed:"
find "$TARGET" -type f | sed "s|$TARGET|  .|"
