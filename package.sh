#!/bin/bash
# Builds the bundle, stages everything the vsix needs and produces dist/<name>-<version>.vsix.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
EXTENSION="$HERE/extension"
DIST="$HERE/dist"

VERSION="$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
  "$EXTENSION/package.json" | head -1)"
NAME="$(sed -n 's/.*"name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
  "$EXTENSION/package.json" | head -1)"

"$HERE/build.sh"

# The marketplace shows the License tab from a LICENSE file inside the packaged folder, and the
# repository keeps only one copy, so it is staged rather than duplicated in git.
cp "$HERE/LICENSE" "$EXTENSION/LICENSE"

if [ ! -f "$EXTENSION/icon.png" ]; then
  echo "==> rendering icon"
  python3 "$HERE/assets/render-icon.py"
fi

mkdir -p "$DIST"
echo "==> packaging $NAME-$VERSION.vsix"
(cd "$EXTENSION" && npx --yes @vscode/vsce package --out "$DIST/$NAME-$VERSION.vsix")

echo
echo "==> contents"
unzip -l "$DIST/$NAME-$VERSION.vsix" | sed -n '4,40p'
echo "==> $DIST/$NAME-$VERSION.vsix"
