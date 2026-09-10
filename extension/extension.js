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
  "projectLayout",
  "runtimeClasspath",
  "maxProjects",
  "outputBase",
  "maxIdleSeconds",
  "commandTimeoutSeconds",
  "discoveryNoFetch",
  "noblockForLock",
  "backoffMaxSeconds",
  "groupSourceRoots",
  "buildOnImport",
  "buildJobs",
  "mavenRepository",
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

/*
  redhat.java's own API is the only honest "the server can take requests now" signal, and everything
  here needs it. Workspace commands used to be sent the moment there was a reason to send one - the
  extension activating, a document opening, the window regaining focus - and those reasons arrive
  while the language client is still starting. A request sent then is not merely dropped: it lands in
  the middle of the client's own start sequence, and a start that does not finish within 30 s is a
  start redhat.java abandons, silently, for a second language server on the same -data directory
  (`pipeStartTimeout`). Two servers there corrupt the shared JDT index. So the ordering is a
  correctness requirement and not politeness.

  The wait is bounded because serverReady() never resolves if the server fails outright, and a
  developer invoking "JBazel: Doctor" on a broken server deserves the error rather than silence.
*/
const SERVER_READY_TIMEOUT_MS = 120000;
let serverReadyPromise = null;

function whenServerReady() {
  if (!serverReadyPromise) {
    const java = vscode.extensions.getExtension("redhat.java");
    const ready = java
      ? Promise.resolve(java.activate()).then((api) =>
          api && typeof api.serverReady === "function" ? api.serverReady() : undefined
        )
      : Promise.resolve();
    serverReadyPromise = Promise.race([
      ready.catch(() => undefined),
      new Promise((resolve) => setTimeout(resolve, SERVER_READY_TIMEOUT_MS)),
    ]);
  }
  return serverReadyPromise;
}

async function serverCommand(command, ...args) {
  await whenServerReady();
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
  const channel = vscode.window.createOutputChannel("JBazel");
  context.subscriptions.push(channel);
  return channel;
}

/*
  The half of the doctor report the server cannot produce. jdt.ls does not hand its own java.*
  configuration to a plugin in any stable way, and reading it through internal preference APIs would
  break on the next redhat.java release - but from here it is one call.
*/
function settingsAdvice() {
  const java = vscode.workspace.getConfiguration("java");
  const lines = ["  java.* settings"];
  const problems = [];

  const maven = java.get("import.maven.enabled");
  const gradle = java.get("import.gradle.enabled");
  lines.push(`    import.maven.enabled  : ${maven}`);
  lines.push(`    import.gradle.enabled : ${gradle}`);
  if (maven || gradle) {
    problems.push(
      "The Maven or Gradle importer is enabled. Bazel owns dependency resolution here, and " +
        "these importers adopt any stray pom.xml or build.gradle they find, then compete for the " +
        "same folders as the generated projects. Set java.import.maven.enabled and " +
        "java.import.gradle.enabled to false."
    );
  }

  const vmargs = java.get("jdt.ls.vmargs") || "";
  lines.push(`    jdt.ls.vmargs         : ${vmargs || "(default)"}`);
  if (!/-Xmx/.test(vmargs)) {
    lines.push(
      "    note                  : no -Xmx set, so the language server runs on redhat.java's " +
        "default. Compare it against the heap the doctor report above measured."
    );
  }
  lines.push(`    autobuild.enabled     : ${java.get("autobuild.enabled")}`);

  /*
    The setting that decides whether the workspace scan walks into bazel-out. The server half of the
    report says which patterns are missing from the list the language server is running with; this
    says what the client is sending it, which is the difference that explains a scan still following
    the symlinks after the importer fenced them off.
  */
  const exclusions = java.get("import.exclusions") || [];
  const inspected = java.inspect("import.exclusions") || {};
  const pinned =
    inspected.workspaceFolderValue !== undefined ||
    inspected.workspaceValue !== undefined ||
    inspected.globalValue !== undefined;
  lines.push(
    `    import.exclusions     : ${exclusions.length} pattern(s), ${pinned ? "set in your settings" : "default"}`
  );
  for (const pattern of exclusions) {
    lines.push(`                            ${pattern}`);
  }
  if (pinned && !exclusions.some((pattern) => String(pattern).includes("bazel"))) {
    lines.push(
      "    note                  : a list set in your settings replaces the extension's default " +
        "rather than\n                            extending it, and nothing in this one mentions " +
        "bazel. The importer writes\n                            the output paths back on every " +
        "attempt and again whenever a settings change\n                            rebuilds the " +
        "list, so what is exposed is the first scan of a session; the\n                          " +
        "  doctor section above prints anything still missing."
    );
  }

  let out = lines.join("\n") + "\n";
  if (problems.length > 0) {
    out += "\n";
    problems.forEach((problem, index) => {
      out += `  ${index + 1}. ${problem}\n`;
    });
  }
  return out;
}

function activate(context) {
  syncSettings();
  const channel = createOutputReport(context);

  const status = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100);
  status.command = "jbazel.showImportReport";
  context.subscriptions.push(status);

  /*
    Surfaces the backoff window. Without it a workspace that is quietly waiting out a failure looks
    identical to one that simply has no dependencies - which is how a broken import went unnoticed
    for a whole night.
  */
  /*
    While the classpath is being resolved the status is polled on its own short interval rather than
    the 30 s one, because that stretch is the whole point of showing it: resolving a package pulled
    in on demand is seconds of bazel, and at 30 s granularity the indicator would appear after the
    work it was meant to describe had finished.
  */
  let soon = null;
  context.subscriptions.push({ dispose: () => soon && clearTimeout(soon) });

  /*
    Outline codicons throughout, not the solid ones. $(alert) and $(warning) are filled triangles and
    read as an error to act on right now; none of these states is that - a fetch that has to be
    fixed, a wait on someone else's bazel, a retry, a resolve in flight, jars that were never built.
    The item sits next to the language server's own indicators, and matching their line weight is the
    difference between information and alarm.
  */
  async function refreshStatus() {
    if (soon) {
      clearTimeout(soon);
      soon = null;
    }
    try {
      const state = await serverCommand("jbazel.status");
      const entries = Object.values(state || {});
      const backoff = entries.reduce((max, entry) => Math.max(max, entry.backoffSeconds || 0), 0);
      const missing = entries.reduce((sum, entry) => sum + (entry.missingJars || 0), 0);
      const resolving = entries.reduce((sum, entry) => sum + (entry.resolving || 0), 0);
      const busy = entries.some((entry) => entry.serverBusy);
      const blocked = entries.find((entry) => entry.needsFix);
      if (blocked) {
        /*
          Ahead of every transient state, because this one is not transient: bazel cannot fetch an
          external repository, so no amount of waiting produces a classpath. Nothing about the
          bazel-* symlinks is reported here any more - they are excluded from the language server's
          build-file scan on every import, and they are what the rest of the repository reads
          generated output through.
        */
        status.text = "$(circle-slash) JBazel: bazel cannot fetch a repository";
        status.tooltip =
          "The classpath cannot be resolved until bazel can fetch its external repositories. " +
          "This does not clear on its own - fix it, then editing MODULE.bazel or running " +
          "'JBazel: Refresh Classpath' retries at once.\n\n" +
          (blocked.needsFixDetail || "See the import report for the full bazel error.");
        status.show();
      } else if (busy) {
        // Not an error: a terminal build holds the bazel server lock and the IDE is waiting it out.
        status.text = "$(watch) JBazel: waiting for another bazel command";
        status.tooltip =
          "A bazel command outside the IDE (usually a terminal build) holds the server lock. " +
          'The classpath refreshes when it finishes. Setting bazelJava.outputBase to "ide" ' +
          "gives the IDE its own server so the two never queue behind each other.";
        status.show();
      } else if (backoff > 0) {
        status.text = `$(history) JBazel: retry in ${backoff}s`;
        status.tooltip = entries.map((entry) => entry.discovery).join("\n");
        status.show();
      } else if (resolving > 0) {
        status.text = `$(sync~spin) JBazel: resolving ${resolving} classpath${
          resolving === 1 ? "" : "s"
        }`;
        status.tooltip =
          "Asking bazel which jars these projects compile against. Types from a package that was " +
          "just pulled in resolve when this finishes.";
        status.show();
        soon = setTimeout(refreshStatus, 1500);
      } else if (missing > 0) {
        status.text = `$(package) JBazel: ${missing} jars not built`;
        status.tooltip = "Run 'JBazel: Build Classpath' to produce them.";
        status.show();
      } else {
        status.hide();
      }
      offerSourcesOnce(entries);
    } catch (error) {
      status.hide();
    }
  }

  /*
    Offered once per workspace and never again, whatever the answer. A repository where most jars
    have no sources is the normal state, not a fault - rules_jvm_external simply never fetches them -
    so this has to read as an offer a developer may decline forever, not as a warning to repeat every
    thirty seconds.
  */
  async function offerSourcesOnce(entries) {
    const jars = entries.reduce((sum, entry) => sum + (entry.classpathJars || 0), 0);
    const withSources = entries.reduce((sum, entry) => sum + (entry.jarsWithSources || 0), 0);
    if (jars < 20 || withSources * 2 >= jars) {
      return;
    }
    const key = `sourcesOffered:${(vscode.workspace.workspaceFolders || [])
      .map((folder) => folder.uri.fsPath)
      .join("|")}`;
    if (context.globalState.get(key)) {
      return;
    }
    await context.globalState.update(key, true);
    const choice = await vscode.window.showInformationMessage(
      `Only ${withSources} of ${jars} library jars have sources attached, so navigating into a ` +
        "library lands in decompiled bytecode. Fetch the source jars?",
      "Fetch",
      "Not now"
    );
    if (choice === "Fetch") {
      await vscode.commands.executeCommand("jbazel.fetchLibrarySources");
    }
  }

  context.subscriptions.push(
    vscode.commands.registerCommand("jbazel.refreshClasspath", async () => {
      syncSettings();
      await showResult(await serverCommand("jbazel.refreshClasspath"));
      refreshStatus();
    }),
    vscode.commands.registerCommand("jbazel.showImportReport", async () => {
      const report = await serverCommand("jbazel.showImportReport");
      channel.clear();
      channel.appendLine(String(report));
      channel.show(true);
    }),
    vscode.commands.registerCommand("jbazel.buildClasspath", async () => {
      await showResult(await serverCommand("jbazel.buildClasspath"));
    }),
    vscode.commands.registerCommand("jbazel.fetchLibrarySources", async () => {
      /*
        Confirmed rather than just started: this downloads one source jar per third-party artifact,
        which is gigabytes on a large repository. It changes nothing about what compiles - only what
        you see when you navigate into a dependency - so it is never worth doing behind someone's
        back.
      */
      const choice = await vscode.window.showInformationMessage(
        "Fetch source jars for every third-party artifact? Without them, navigating into a " +
          "library lands in decompiled bytecode. Expect a large download (gigabytes on a big " +
          "repository); the classpath updates by itself when it finishes.",
        { modal: true },
        "Fetch"
      );
      if (choice === "Fetch") {
        await showResult(await serverCommand("jbazel.fetchLibrarySources"));
      }
    }),
    vscode.commands.registerCommand("jbazel.doctor", async () => {
      const report = await serverCommand("jbazel.doctor");
      channel.clear();
      channel.appendLine(String(report));
      channel.appendLine(settingsAdvice());
      channel.show(true);
    }),
    vscode.workspace.onDidChangeConfiguration(async (event) => {
      if (!event.affectsConfiguration("bazelJava")) {
        return;
      }
      if (syncSettings().length === 0) {
        return;
      }
      const choice = await vscode.window.showInformationMessage(
        "JBazel settings changed. Reimport the workspace now?",
        "Reimport",
        "Later"
      );
      if (choice === "Reimport") {
        await serverCommand("jbazel.refreshClasspath");
      }
    })
  );

  /*
    With a narrowed import scope, opening a file outside it would otherwise get no classpath at all -
    and worse than none, because the language server's own fallback then claims the file and infers a
    source root from its package declaration. Asking the server to provision just that package costs
    one scoped query, and the question of whether it needs asking costs nothing, so this is safe to
    send on every open.
  */
  /*
    Progress only for the step that is known to be slow. Which step that is comes from the server:
    importPlan answers from the discovery cache without taking a single lock, so it returns while the
    workspace is still being indexed, and only a "needed" answer means bazel is about to be run. A
    spinner raised before that answer is a spinner that says a package is being imported when most of
    the time nothing is - and, because the answer arrives late while the index is being built, it says
    so for minutes. The status bar then carries the classpath resolution that follows, so the two
    together cover the whole wait rather than its first half.
  */
  async function withProgressIfSlow(title, work) {
    const promise = work();
    let settled = false;
    const mark = () => {
      settled = true;
    };
    promise.then(mark, mark);
    await new Promise((resolve) => setTimeout(resolve, 400));
    if (settled) {
      return promise;
    }
    return vscode.window.withProgress(
      { location: vscode.ProgressLocation.Window, title },
      () => promise
    );
  }

  const requested = new Set();

  async function claimDocument(document) {
      if (document.languageId !== "java" || document.uri.scheme !== "file") {
        return;
      }
      if (requested.has(document.uri.fsPath)) {
        return;
      }
      requested.add(document.uri.fsPath);
      const name = path.basename(document.uri.fsPath);
      try {
        /*
          Awaited before anything is sent. Editors restored with the window open their documents
          before the server is ready, and a request sent into a language client that is still
          starting is the thing that costs a second server on the same workspace.
        */
        await whenServerReady();
        const plan = String(
          (await serverCommand("jbazel.importPlan", document.uri.fsPath)) || ""
        );
        if (plan !== "needed") {
          if (plan !== "covered") {
            // Backing off, eager mode, or no BUILD file above the file. Any of those can be true
            // now and false later, so the next open of this file asks again.
            requested.delete(document.uri.fsPath);
          }
          return;
        }
        const answer = String(
          (await withProgressIfSlow(`JBazel: importing the package that owns ${name}`, () =>
            serverCommand("jbazel.importFile", document.uri.fsPath)
          )) || ""
        );
        if (answer && !answer.startsWith("Already imported")) {
          channel.appendLine(`${name}: ${answer}`);
          refreshStatus();
        }
      } catch (error) {
        // The server may not be up yet; the next file opened will try again.
        requested.delete(document.uri.fsPath);
      }
  }

  context.subscriptions.push(vscode.workspace.onDidOpenTextDocument(claimDocument));

  /*
    The documents that are already open when this activates, which on a restored window is all of
    them. onDidOpenTextDocument does not fire for those - this extension activates on the first java
    document, so that document, and every tab restored with it, has already been opened and already
    been resolved by the language server against whatever existed at the time, which is nothing.
    Those are exactly the files that stay red until they are clicked.

    Retried, because the first pass can be too early for a different reason: the server only knows
    about a bazel workspace once its importer has run, and until then it answers that it can do
    nothing for the file. Measured on a restored window: eleven seconds between the server reporting
    itself ready and the import finishing. A single pass inside that window would be a pass that
    gives up on every tab.
  */
  const SWEEP_ATTEMPTS = 8;
  let sweepTimer = null;

  async function sweepOpenDocuments(attempt) {
    await Promise.all(vscode.workspace.textDocuments.map(claimDocument));
    const unclaimed = vscode.workspace.textDocuments.some(
      (document) =>
        document.languageId === "java" &&
        document.uri.scheme === "file" &&
        !requested.has(document.uri.fsPath)
    );
    if (unclaimed && attempt < SWEEP_ATTEMPTS) {
      sweepTimer = setTimeout(
        () => sweepOpenDocuments(attempt + 1),
        Math.min(8000, 1000 * 2 ** attempt)
      );
    }
  }

  context.subscriptions.push({
    dispose: () => {
      if (sweepTimer) {
        clearTimeout(sweepTimer);
      }
    },
  });
  sweepOpenDocuments(0);

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
        await serverCommand("jbazel.buildFilesChanged");
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

  /*
    A jar the developer's own bazel rewrote keeps its path, and the language server caches what it
    read from every jar outside the workspace, so a class compiled minutes ago can stay unresolved in
    the editor until the window is reloaded. Nothing in the repository changed that anyone watches -
    the BUILD files are untouched by a plain rebuild - so the server has to be told to look, and the
    look is one stat per classpath jar.

    Hence the cues rather than a signal: the window regaining focus, a terminal command finishing
    (only where the shell integration reports it), the editor coming back to the front after the
    terminal panel. They fire together and they fire often, so both sides throttle - here to keep
    the round trips down, in the server to keep the java model out of it.
  */
  let lastSync = 0;
  const syncClasspathJars = async () => {
    const now = Date.now();
    if (now - lastSync < 2000) {
      return;
    }
    lastSync = now;
    try {
      await serverCommand("jbazel.syncClasspathJars");
    } catch (error) {
      // Nothing imported yet, or the server is still starting; the next cue tries again.
      lastSync = 0;
    }
  };
  context.subscriptions.push(
    vscode.window.onDidChangeWindowState((state) => {
      if (state.focused) {
        syncClasspathJars();
      }
    }),
    vscode.window.onDidChangeActiveTextEditor(() => syncClasspathJars())
  );
  if (typeof vscode.window.onDidEndTerminalShellExecution === "function") {
    context.subscriptions.push(
      vscode.window.onDidEndTerminalShellExecution(() => syncClasspathJars())
    );
  }

  const timer = setInterval(refreshStatus, 30000);
  context.subscriptions.push({ dispose: () => clearInterval(timer) });
  refreshStatus();
}

function deactivate() {}

module.exports = { activate, deactivate };
