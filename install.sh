#!/bin/bash
# Builds a vsix and installs it, so the local install goes through exactly the artifact that gets
# published. Hand-copying into ~/.vscode/extensions is what used to leave two copies of the bundle
# registered with jdt.ls at once.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"

CODE="${CODE:-$(command -v code || true)}"
if [ -z "$CODE" ]; then
  for candidate in \
    "/Applications/Visual Studio Code.app/Contents/Resources/app/bin/code" \
    "$HOME/Applications/Visual Studio Code.app/Contents/Resources/app/bin/code"; do
    [ -x "$candidate" ] && CODE="$candidate" && break
  done
fi

"$HERE/package.sh"
VSIX="$(ls -t "$HERE"/dist/*.vsix | head -1)"

if [ -z "$CODE" ]; then
  echo
  echo "The 'code' CLI is not on PATH (VS Code: Shell Command: Install 'code' command in PATH)."
  echo "Install by hand: Extensions view -> ... -> Install from VSIX -> $VSIX"
  exit 0
fi

echo "==> installing $VSIX"
"$CODE" --install-extension "$VSIX" --force
echo "==> reload the VS Code window"
