const crypto = require("crypto");
const fs = require("fs");
const os = require("os");
const path = require("path");
const vscode = require("vscode");

const SETTINGS_KEYS = [
  "binary",
  "targets",
  "excludeTargets",
  "useBazelProject",
  "importMode",
  "maxProjects",
  "outputBase",
  "maxIdleSeconds",
  "commandTimeoutSeconds",
  "discoveryNoFetch",
  "noblockForLock",
  "backoffMaxSeconds",
  "groupSourceRoots",
  "buildOnImport",
];

/*
  The language server cannot see bazelJava.* settings: redhat.java forwards only the java.*
  namespace, and the importer runs before any extension could push anything over executeCommand.
  Setting process.env from here does not work either - redhat.java is an extensionDependency, so it
  has already spawned the server by the time this activates. That was the long-standing reason
  bazelJava.binary silently did nothing.

  So the settings are mirrored to a file the server reads at import time, keyed by workspace folder
  and kept outside the repository.
*/
function settingsFile(folder) {
  const hash = crypto.createHash("sha256").update(folder).digest("hex").slice(0, 16);
  return path.join(os.homedir(), ".cache", "bazel-java-jdtls", `settings-${hash}.json`);
}

function collectSettings(folderUri) {
  const configuration = vscode.workspace.getConfiguration("bazelJava", folderUri);
  const settings = {};
  for (const key of SETTINGS_KEYS) {
    const inspected = configuration.inspect(key);
    const value = configuration.get(key);
    const isDefault =
      inspected && JSON.stringify(value) === JSON.stringify(inspected.defaultValue);
    // Only write what the user actually set: an explicit default here would override a value from
    // the repository's own .vscode/bazel-java.json.
    if (!isDefault) {
      settings[key] = value;
    }
  }
  return settings;
}

function syncSettings() {
  const folders = vscode.workspace.workspaceFolders || [];
  const written = [];
  for (const folder of folders) {
    if (folder.uri.scheme !== "file") {
      continue;
    }
    const target = settingsFile(folder.uri.fsPath);
    const settings = collectSettings(folder.uri);
    try {
      fs.mkdirSync(path.dirname(target), { recursive: true });
      const next = JSON.stringify(settings, null, 2);
      const current = fs.existsSync(target) ? fs.readFileSync(target, "utf8") : "";
      if (current !== next) {
        fs.writeFileSync(target, next);
        written.push(folder.uri.fsPath);
      }
    } catch (error) {
      console.warn(`bazel-java: cannot write ${target}: ${error}`);
    }
  }
  return written;
}

async function serverCommand(command, ...args) {
  return vscode.commands.executeCommand("java.execute.workspaceCommand", command, ...args);
}

async function showResult(result) {
  if (typeof result === "string") {
    vscode.window.showInformationMessage(result);
  } else if (result) {
    vscode.window.showInformationMessage(JSON.stringify(result));
  }
}

function createOutputReport(context) {
  const channel = vscode.window.createOutputChannel("Bazel Java");
  context.subscriptions.push(channel);
  return channel;
}

function activate(context) {
  syncSettings();
  const channel = createOutputReport(context);

  const status = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100);
  status.command = "bazelJava.showImportReport";
  context.subscriptions.push(status);

  /*
    Surfaces the backoff window. Without it a workspace that is quietly waiting out a failure looks
    identical to one that simply has no dependencies - which is how a broken import went unnoticed
    for a whole night.
  */
  async function refreshStatus() {
    try {
      const state = await serverCommand("bazel.status");
      const entries = Object.values(state || {});
      const backoff = entries.reduce((max, entry) => Math.max(max, entry.backoffSeconds || 0), 0);
      const missing = entries.reduce((sum, entry) => sum + (entry.missingJars || 0), 0);
      const busy = entries.some((entry) => entry.serverBusy);
      if (busy) {
        // Not an error: a terminal build holds the bazel server lock and the IDE is waiting it out.
        status.text = "$(watch) Bazel: waiting for another bazel command";
        status.tooltip =
          "A bazel command outside the IDE (usually a terminal build) holds the server lock. " +
          'The classpath refreshes when it finishes. Setting bazelJava.outputBase to "ide" ' +
          "gives the IDE its own server so the two never queue behind each other.";
        status.show();
      } else if (backoff > 0) {
        status.text = `$(warning) Bazel: retry in ${backoff}s`;
        status.tooltip = entries.map((entry) => entry.discovery).join("\n");
        status.show();
      } else if (missing > 0) {
        status.text = `$(warning) Bazel: ${missing} jars not built`;
        status.tooltip = "Run 'Bazel: Build Classpath' to produce them.";
        status.show();
      } else {
        status.hide();
      }
    } catch (error) {
      status.hide();
    }
  }

  context.subscriptions.push(
    vscode.commands.registerCommand("bazelJava.refreshClasspath", async () => {
      syncSettings();
      await showResult(await serverCommand("bazel.refreshClasspath"));
      refreshStatus();
    }),
    vscode.commands.registerCommand("bazelJava.showImportReport", async () => {
      const report = await serverCommand("bazel.showImportReport");
      channel.clear();
      channel.appendLine(String(report));
      channel.show(true);
    }),
    vscode.commands.registerCommand("bazelJava.buildClasspath", async () => {
      await showResult(await serverCommand("bazel.buildClasspath"));
    }),
    vscode.workspace.onDidChangeConfiguration(async (event) => {
      if (!event.affectsConfiguration("bazelJava")) {
        return;
      }
      if (syncSettings().length === 0) {
        return;
      }
      const choice = await vscode.window.showInformationMessage(
        "Bazel Java settings changed. Reimport the workspace now?",
        "Reimport",
        "Later"
      );
      if (choice === "Reimport") {
        await serverCommand("bazel.refreshClasspath");
      }
    })
  );

  /*
    With a narrowed import scope, opening a file outside it would otherwise get no classpath at all.
    Asking the server to provision just that package costs one scoped query; the server answers
    "already imported" cheaply when the file is covered, so this is safe to send on every open.
  */
  const requested = new Set();
  context.subscriptions.push(
    vscode.workspace.onDidOpenTextDocument(async (document) => {
      if (document.languageId !== "java" || document.uri.scheme !== "file") {
        return;
      }
      if (requested.has(document.uri.fsPath)) {
        return;
      }
      requested.add(document.uri.fsPath);
      try {
        await serverCommand("bazel.importFile", document.uri.fsPath);
      } catch (error) {
        // The server may not be up yet; the next file opened will try again.
        requested.delete(document.uri.fsPath);
      }
    })
  );

  /*
    The server cannot watch BUILD files itself: source folders are linked into the projects at
    src/main/java, while BUILD.bazel sits one level above that, outside every linked resource. So the
    watch lives here, where VS Code already has a real filesystem watcher over the workspace.
  */
  const watcher = vscode.workspace.createFileSystemWatcher(
    "**/{BUILD,BUILD.bazel,MODULE.bazel,WORKSPACE,WORKSPACE.bazel,*.bzl}"
  );
  let pending = null;
  const buildFilesChanged = () => {
    if (pending) {
      clearTimeout(pending);
    }
    // Debounced: a branch switch or a formatter run touches many BUILD files at once, and each
    // refresh is a bazel query plus an aquery.
    pending = setTimeout(async () => {
      pending = null;
      try {
        await serverCommand("bazel.buildFilesChanged");
        refreshStatus();
      } catch (error) {
        // Server not ready; the next edit will retry.
      }
    }, 3000);
  };
  watcher.onDidChange(buildFilesChanged);
  watcher.onDidCreate(buildFilesChanged);
  watcher.onDidDelete(buildFilesChanged);
  context.subscriptions.push(watcher, {
    dispose: () => pending && clearTimeout(pending),
  });

  const timer = setInterval(refreshStatus, 30000);
  context.subscriptions.push({ dispose: () => clearInterval(timer) });
  refreshStatus();
}

function deactivate() {}

module.exports = { activate, deactivate };
