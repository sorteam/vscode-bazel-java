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
# Both process names, because the executable was renamed between VS Code releases: 1.136 ran the
# main process as MacOS/Electron, 1.137 runs it as MacOS/Code. Matching only the first meant this
# guard silently stopped guarding - and an install that goes through under a running editor is
# exactly the two-servers-on-one-workspace case it exists to prevent. The language server processes
# are matched too, since they outlive the editor when the platform deadlocks.
if pgrep -f "Visual Studio Code.app/Contents/MacOS/(Electron|Code)$" >/dev/null 2>&1 \
  || pgrep -f "Visual Studio Code.app/Contents/MacOS/Electron" >/dev/null 2>&1 \
  || pgrep -f "Visual Studio Code.app/Contents/MacOS/Code" >/dev/null 2>&1 \
  || pgrep -f "equinox.launcher" >/dev/null 2>&1; then
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

ID="$(python3 - "$HERE/extension/package.json" <<'PYEOF'
import json, sys
d = json.load(open(sys.argv[1]))
print(d["publisher"] + "." + d["name"])
PYEOF
)"

# Uninstalled first, deliberately. "--install-extension --force" over a vsix carrying the version
# that is already installed marks the existing directory obsolete and unpacks into the same
# directory name, and what is left is an extension folder listed in .obsolete and absent from
# extensions.json - installed as far as this script can see, gone as far as VS Code is concerned. The
# jdt.ls bundle then never reaches the language server, and on a monorepo that is not a quiet
# degradation: with no importer to fence off the output tree, jdt.ls's own gradle, maven and eclipse
# detection walks the whole repository through the bazel symlinks. Measured on this failure, from the
# server's own log: "Workspace initialized in 101469ms", twice, and a language client that gave up
# and restarted in between.
if "$CODE" --list-extensions 2>/dev/null | grep -qx "$ID"; then
  echo "==> removing the installed $ID first"
  "$CODE" --uninstall-extension "$ID" >/dev/null 2>&1 || true
fi

echo "==> installing $VSIX"
"$CODE" --install-extension "$VSIX" --force

# Verified rather than trusted, because the failure above is silent from here: the CLI reports
# success either way.
if ! "$CODE" --list-extensions 2>/dev/null | grep -qx "$ID"; then
  echo "error: $ID installed but VS Code does not list it. Remove" >&2
  echo "         ~/.vscode/extensions/$ID-*" >&2
  echo "       and the matching entry from ~/.vscode/extensions/.obsolete, then re-run." >&2
  exit 1
fi
OBSOLETE="$HOME/.vscode/extensions/.obsolete"
if [ -s "$OBSOLETE" ] && grep -q "\"$ID-" "$OBSOLETE"; then
  echo "error: VS Code has $ID marked obsolete; it will not load." >&2
  echo "       Delete $OBSOLETE and re-run." >&2
  exit 1
fi
echo "==> $ID installed and registered"
echo "==> reload the VS Code window"
