#!/bin/bash
# Builds a vsix and installs it, so the local install goes through exactly the artifact that gets
# published. Hand-copying into ~/.vscode/extensions is what used to leave two copies of the bundle
# registered with jdt.ls at once.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"

# Installing over a running VS Code is not a shortcut, it is the bug: replacing a bundle listed in
# contributes.javaExtensions makes redhat.java restart the language server to synchronise bundles,
# the restarted client re-registers every command the server advertises, and if another jdt.ls
# extension holds one of those names already (vscode-spring-boot registers
# sts.java.addClasspathListener) the client initialisation fails with "command already exists". The
# client then starts a second server and does not stop the first, so two language servers end up on
# one -data directory writing one JDT index. That index does not survive it: it comes back with
# garbage length fields ("Failed to read index data ... size 1349676899"), and JDT allocates them.
# Measured once, and once was enough - two orphaned servers at 8.4 GB and 2.7 GB, a corrupt index and
# a full reindex to recover.
if pgrep -f "Visual Studio Code.app/Contents/MacOS/Electron" >/dev/null 2>&1 \
  || pgrep -f "redhat.java-.*/jre/.*/bin/java" >/dev/null 2>&1; then
  if [ -z "${FORCE:-}" ]; then
    echo "error: VS Code is running. Installing over it can leave two language servers on one" >&2
    echo "       workspace and a corrupt JDT index; quit VS Code first." >&2
    echo "       Re-run with FORCE=1 to install anyway (then quit and reopen VS Code, do not just" >&2
    echo "       reload the window: a reload keeps the extension host, and with it the stale" >&2
    echo "       command registrations)." >&2
    exit 1
  fi
  echo "==> WARNING: VS Code is running; quit and reopen it after this, not Reload Window"
fi

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
