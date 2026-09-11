# Changelog

## 0.8.4

- **Opening the editor no longer runs a bazel analysis and a build of the whole repository.** This is
  the cause of the failure that kept coming back, and it was not where it looked. The plugin decided
  which labels still needed querying by checking an in-memory map - which is empty in a new process -
  so the classpaths its previous session had already resolved and written to disk were never seen,
  and every start re-ran an aquery over every label. The runtime-classpath pass is driven by the same
  list, so it re-ran a cquery too. And `buildOnImport` meant "once per session", where a session is
  every window: a build of every discovered target, every time.

  Measured on a 116-project workspace whose cached import was valid and whose containers JDT had
  already restored: 233 labels analysed, 232 more for the runtime pass, 233 targets built - and 0
  containers published, because nothing had changed. Warm that is about four seconds of bazel. Cold -
  the first start after a reboot, after a branch switch, after anything that dropped bazel's analysis
  cache - it is a full analysis and build of the repository, minutes of every core and gigabytes of
  memory, spent while the editor is still starting its other language servers. That is the machine
  the next window has to start on, and the language client gives a start 30 s before it abandons the
  server and launches a second one on the same workspace directory - which is where
  `command 'sts.java.addClasspathListener' already exists`, the retry loop behind it (15 attempts a
  second, 2054 failures in three and a half minutes) and the 9.3 GB language server all come from.

  The on-disk cache is now consulted before bazel is; the background build is started only for the
  labels whose classpath entries are genuinely missing from disk, and only those labels are built;
  the repository-wide build-file walk happens only when its result is going to be used; and a project
  whose container JDT restored is no longer resolved twice on every start. A start where nothing
  changed now runs **no bazel at all** - verified by pointing the bazel binary at a wrapper that logs
  every invocation and counting zero across two consecutive starts.

- **The container initializer hands JDT back the container it already has.** JDT calls it once per
  project while restoring the java model, inside the window the language client is timing. JDT
  persists every container it was given and restores the entries beforehand; handing that same object
  straight back is the one case `setClasspathContainer` short-circuits completely - no classpath
  delta, no re-resolution, nothing queued for the indexer. An empty placeholder, by contrast, is a
  change from "N jars" to none, and on that delta JDT drops the index of every jar that left the
  classpath and is not shared, then rebuilds it when the real container arrives a moment later - a
  flip paid on every start. The placeholder is now used only where there is no previous session to
  restore from. Measured: 117 containers seeded in 5 ms.

## 0.8.3

- **The second launch of a workspace no longer takes longer than the language client will wait.**
  JDT initializes one classpath container per project while it restores the java model, and on a warm
  workspace it does that inside the `initialize` request - inside the 30 s the client gives the whole
  start before it abandons the server and starts a second one on the same workspace directory.
  Measured on a 116-project workspace: a cold start answered `initialize` 3.6 s after receiving it
  and finished in 19.9 s; the next start, same repository and machine with the projects now on disk,
  answered it 16.7 s later and finished in 31.5 s. One and a half seconds over - and the duplicate
  server, the command-registration collision, the retry loop and the memory that followed all came
  from that. It is also exactly why the failure looked like "the first launch is fine, the second one
  is not".

  The initializer now hands JDT an empty placeholder container and returns; the resolve job publishes
  the real one immediately afterwards, off the start path. That is the path a cold import already
  used, and it is still one publish per project, so nothing is indexed twice. How long the
  placeholders took is logged in batches, so a slow start says where the time actually went.

- **`install.sh` guards against a running editor again.** It looked for the main process as
  `MacOS/Electron`, which is what VS Code 1.136 was called; 1.137 renamed it to `MacOS/Code`, and the
  guard had silently stopped guarding. Installing over a running editor replaces a bundle under a
  live language server, which is the two-servers-on-one-workspace case the guard exists to prevent.
  Both names are matched now, and so are the language server processes, which outlive the editor when
  the platform deadlocks.

- **A language server that cannot shut down now ends itself instead of being killed by hand.** The
  state described below leaves nothing in the platform able to run, and the exit path is part of
  nothing: the language server's own parent-process watcher calls `LanguageServer.exit()` and
  `LanguageServerApplication.exit()` before it schedules its hard fallback, so a lock held under
  either of those means the fallback is never scheduled. Measured: a process whose editor had closed,
  reparented to init, 1.4 GB resident, still there four minutes later.

  That process is not just wasted memory - it holds the lock on its own workspace directory, which is
  what makes the *next* start exceed the client's 30 s budget and take the two-server path, and what
  makes "clean the workspace" stop half way and leave a workspace that is inconsistent rather than
  empty. So the plugin now runs a watchdog: one daemon thread, no job, no scheduling rule, nothing in
  its loop that a platform lock can block, and `halt` rather than `exit` because a shutdown hook is a
  thing that can block. It waits well past the language server's own ~40 s detection window, since
  interrupting a legitimate workspace save is itself how metadata gets truncated. Covered by a test
  that starts a JVM under a shell, kills the shell, and requires the JVM to survive the grace period
  and then end itself.

- **`JBazel: Doctor` names the reason a language server outlives the editor.** Measured from a thread
  dump of a server still holding 1.4 GB with the editor closed and its parent gone: an extension
  bundle's job-change listener runs inside `JobManager.withWriteLock`, walks every project, and makes
  one blocking client round trip per project with no timeout. With the editor gone nothing answers,
  the join never returns, the job manager's write lock is never released, and the process can no
  longer save the workspace or shut down - it has to be killed. Until it is, it holds the lock on the
  language server's workspace directory, which is what makes the next start exceed the client's 30 s
  budget (and take the two-server path) and what makes "clean the workspace" stop half way with
  `ENOTEMPTY`, leaving a workspace that is inconsistent rather than empty - after which the log fills
  with thousands of `Failed to create linked resource` and `does not exist` traces, which is the
  out-of-memory. Nothing in this plugin can prevent that, and this plugin is what makes it reachable:
  the listener's cost is per project. So the doctor reports the bundle and the project count.

- **The language server exits when it is told to, so the next one can start.** Shutting down an
  IDE-owned bazel server was done from a JVM shutdown hook, and it waited: it read the client's
  output to EOF with no timeout, on a `bazel shutdown` that blocks for as long as whoever holds the
  output base keeps it. The JVM does not exit until every hook returns, so this was a language server
  that could take tens of seconds to die, or not die at all - and one that has not died still holds
  the lock on its `-data` directory. The shutdown is now started and abandoned, with
  `--noblock_for_lock`; `--max_idle_secs` is what guarantees the server goes away. The cache is no
  longer saved from the hook either: every path that changes it already saves before returning, so
  the save on the way out could only write megabytes into a metadata directory jdt.ls may already be
  deleting - which is where `ENOTEMPTY` on "clean workspace" came from.

- **Initializing the workspace repeatedly no longer costs the repository each time.** When a language
  client's start runs past its 30 s budget it starts a second server on the same workspace directory
  and leaves the first running; the second one then collides with the commands the first registered
  and retries, which re-initializes the workspace about twice a second for as long as the window is
  open. Nothing in this plugin can stop that loop, but paying for a full import on every turn of it is
  what made it fatal rather than noisy: measured at ~600 full imports of 116 projects in five minutes,
  with 11 MB of server log to match. A full import within seconds of the last one is now skipped,
  keeping the projects already provisioned. `JBazel: Doctor` also names the state outright now,
  because every symptom of it points somewhere else - java processes that will not die, a machine out
  of memory, an import that runs over and over.

- **A file outside the import scope no longer resolves against a guessed source root.** Two separate
  causes, one symptom - `The declared package "..." does not match the expected package ""`.

  jdt.ls asks each importer whether it applies and stops only when one reports the folder *resolved*;
  after this importer come gradle, maven, eclipse and a fallback that claims the folder wholesale.
  Reporting "resolved" was conditional on having provisioned at least one project, so a narrowed
  import scope, a backoff window after a failed query, or a client that re-initialized mid-round all
  handed the whole repository to that fallback. A bazel workspace is now reported resolved even when
  the import provisioned nothing; the one case that hands the folder on is discovery running,
  succeeding, and finding no java at all.

  The fallback project is also created on the *open* of a file no project covers, which is a race
  on-demand importing cannot win - both start from the same `didOpen`. Once it exists its linked
  folder covers the whole repository, so every file maps to two resources and which one wins is
  iteration order. It is now dropped as soon as the file that provoked it has a real project. A file
  that genuinely cannot be placed keeps the fallback - for that file a guessed source root beats
  nothing.

- **A file that was already open when the window opened no longer stays red until it is clicked.**
  jdt.ls resolves a document exactly once, on its open, and it has a second fallback for a file no
  project covers: a linked "fake compilation unit" in its own `jdt.ls-java-project`. On a restored
  window every tab is opened before this plugin's import has provisioned anything, so every tab
  landed there - measured: 25 tabs across 12 services, each one then failing forever with
  `Error in Java Model (code 969): X.java [in ... [in src [in jdt.ls-java-project]]] does not exist`.
  Clicking the file worked because clicking it is a fresh open.

  Provisioning the project does not fix it on its own, and neither does deleting the project that
  claimed the file: nothing recomputes what was published for that document. So the plugin now asks
  jdt.ls to validate the document again - `validateDocument` re-resolves the compilation unit from
  the URI, which is the step that has to be repeated - and it waits for a real workspace resource to
  exist before asking, since a file can be inside the imported scope by the cache while its project
  is still being written. The extension also sweeps the documents that were already open when it
  activated, retrying while the server still reports that it knows nothing about the workspace:
  `onDidOpenTextDocument` never fires for those, and the gap between the server reporting itself
  ready and the import finishing was eleven seconds on the repository this was measured on.

- **The spinner on opening a file tells the truth.** Deciding whether a file needs importing reads the
  discovery cache and takes no lock, so it answers in microseconds even mid-index; the import itself
  runs bazel and takes seconds. Both used to be one request with one progress notification, which put
  *importing the package that owns X* on the screen while the workspace was at 63% of its index and
  the answer, arriving minutes later, was that the file had been imported all along. The extension now
  asks first and raises progress only for the step that will actually run bazel. An on-demand import
  is also recorded in the discovery cache, so the next file opened in that package is answered from
  the cache instead of querying bazel for a package already imported.

## 0.8.2

- **A jar that bazel rebuilt is re-read, so a class built a minute ago is a class the editor knows
  about.** Every entry on these classpaths is an absolute path outside the workspace, which JDT
  calls an external archive and treats as immutable: it remembers each one's modification time in
  the java model and compares that memory against the disk at exactly two moments - when the
  language server starts, and when `refreshExternalArchives` is called. Nothing called it.

  So a rebuild that changes a jar without changing its path - a generated API library after its
  spec was edited, any library after an ordinary `bazel build` - left the editor resolving imports
  against the jar as it had been read hours earlier. Measured: a jar holding the new type since
  12:28, a full classpath refresh at 12:51 that republished 42 containers, and the model's record of
  that jar still reading 12:21 the day before. `The import ... cannot be resolved`, on a class that
  had been on disk for twenty minutes, until the window was reloaded - a full reindex of the
  repository to pick up one jar.

  [ExternalArchives](../server/src/io/github/sorteam/bazel/jdtls/ExternalArchives.java) closes it.
  Every classpath resolve ends by checking the jars behind the projects it just resolved, and the
  extension asks for the same check when the window regains focus, when a terminal command finishes
  and when an editor comes back to the front - because a build run in a terminal rewrites jars and
  tells the IDE nothing at all.

  The check is deliberately not `refreshExternalArchives` itself. That call is not a check: it takes
  the java model lock, walks every archive of every project in scope and queues each moved one for
  indexing, so a pass over a whole workspace with stale recorded timestamps is minutes of indexing
  under a lock. Minutes matter here beyond being slow - redhat.java races the LSP handshake against
  30 s and then starts a *second* language server on the same `-data` directory without stopping the
  first, and two servers there write one JDT index until it comes back with garbage length fields
  that JDT allocates. So this plugin does the cheap half where no lock is held: it stats the jars
  behind the published containers, once per distinct path (a large workspace shares ~1.6k jars across
  ~50k container entries), and hands JDT only the projects whose jars actually moved. The first pass
  seeds and reports nothing, since the language server refreshed those archives itself during
  startup. A pass that finds nothing costs stats and touches neither the model lock nor the index.
- **Containers are no longer republished for a jar whose content changed** - the republish that was
  supposed to be how those changes reached JDT, and demonstrably was not. It compares classpath
  entries, the entry is identical, no delta fires; what it did cost was JDT dropping and re-indexing
  every jar behind the container, ~1.6k of them on a large repository, which is most of what "the
  java process is busy for minutes after a build" was. The
  [ContainerStamp](../server/src/io/github/sorteam/bazel/jdtls/ContainerStamp.java) now covers what
  the container actually says - which jars, in which order, which of them exist, and the source jar
  attached to each - and content is left to the archive refresh above.

- **Nothing is sent to the language server before it says it is ready.** The extension used to fire
  workspace commands on the first reason it had - activating, a document opening, a 30 s status poll
  - and those reasons arrive while redhat.java's language client is still starting. That window is
  not a safe place to be: a client start that does not finish within 30 s is one redhat.java
  abandons, silently, for a *second* language server on the same `-data` directory
  (`pipeStartTimeout: 3e4`, and the catch clause stops neither the first client nor its process).
  Two servers there write one JDT index and it comes back with garbage length fields.

  Measured while looking for the cost: with a real 116-project `-data` directory and all 41 jdt.ls
  extension bundles, the server answers `initialize` in 3.4 s, and this plugin's bundle accounts for
  70 ms of that - so the budget is spent on the client side, which is exactly where an ill-timed
  request lands. Every command now waits on redhat.java's own `serverReady()` first, with a bounded
  wait so a server that fails to start still reports an error instead of hanging.
- **"Clean Java Language Server Workspace" works again.** It failed with `ENOTEMPTY: directory not
  empty, rmdir .../.metadata/.plugins` every time, and the clean the developer asked for simply did
  not happen. The import cache is written into the language server's instance area, the clean has
  the client delete that area recursively while the server process is exiting, and the shutdown hook
  that persists the cache created its directory straight back into the middle of that delete - along
  with a temp file and 17 MB of JSON. The save now treats the surrounding `.plugins` area as the
  authority: if it is gone the metadata is being thrown away, this cache with it, and nothing is
  written. Only the store's own subdirectory is ever created.
- **A stale container stamp no longer costs a reindex.** The stamp records what this plugin handed
  JDT, and it can be stale for reasons that say nothing about the classpath: its own format changed
  in an upgrade, the metadata was cleaned, a session ended badly. Publishing on that basis re-reads
  and re-indexes every jar behind every container, and on a large repository that is minutes -
  minutes that redhat.java does not grant. It races the handshake against a 30 s timeout
  (`pipeStartTimeout`) and then starts a *second* language server on the same `-data` directory
  without stopping the first; the two corrupt the shared JDT index, which comes back with garbage
  length fields that JDT dutifully allocates. Observed: two servers at 8.4 GB and 2.7 GB, and an
  editor resolving nothing. The publish site now asks JDT what container it is holding before it
  acts on a stamp mismatch, and corrects the stamp instead of republishing - so upgrading to this
  version costs no reindex at all.

  On a workspace this size, `"java.transport": "stdio"` is worth setting regardless: it makes
  redhat.java skip that race entirely.

## 0.8.1

- **The runtime jars are handed to launches instead of being put on the project's classpath.** 0.8.0
  merged them into the classpath, which fixed launching and broke two other things: on a
  116-project workspace the classpath went from 67k entries to 106k, the language server's heap with
  it (12 GB measured), and the editor began accepting code the build rejects, since JDT has one
  classpath per project and no runtime scope.

  JDT has the seam for exactly this - `org.eclipse.jdt.launching.runtimeClasspathEntryResolvers`,
  the mechanism m2e uses to keep maven scopes apart - so the container stays compile-only and the
  runtime jars exist only in the answer given to a launch. Compilation mirrors the build again,
  memory returns to where it was, and an application still starts with its `runtime_deps`.

  The import cache format changed with it (runtime jars now live in their own map), so the first
  import after upgrading re-runs discovery once.

## 0.8.0

- **`runtime_deps` are on the classpath now, so an application launched from the IDE runs with the
  jars bazel would give it.** Everything here reads the classpath off the Javac action, which
  describes compilation exactly and cannot describe running: `runtime_deps` are not inputs to javac.
  A jdbc driver, a logging backend or a flyway module declared there was simply absent, so the
  application died on startup while `bazel run` worked. A second pass asks `bazel cquery` for
  `JavaInfo.transitive_runtime_jars` - analysis, no actions, one call for every label at once - and
  merges the answer into the classpath, compile entries first.

  Measured on a 116-project repository: about a second for the query, and for one service 241
  runtime jars including the postgres driver that its `runtime_deps` declares. A failure of that
  query leaves the compile classpath untouched and says so once; an import that finishes matters
  more than a launch that works.

  `bazelJava.runtimeClasspath: false` turns it off. The trade it makes: JDT has one classpath per
  project, so with runtime jars on it, code written against a runtime-only dependency compiles in
  the editor and fails in the build.
- `JBazel: Doctor` reports whether the runtime classpath is on.

## 0.7.2

- **Running an application from the Spring Boot Dashboard no longer dies with `ClassFormatError`.**
  0.7.1 replaced bazel's `header_*.jar` ABI jars with the real maven artifacts, but the repository's
  own targets get an ABI jar under a different name - ijar and turbine write `liblibrary-ijar.jar`
  and `-hjar.jar` - and those were still going onto the classpath. An ABI jar has signatures and no
  method bodies, which compiles fine and refuses to load: `Absent Code attribute in method that is
  not native or abstract`, on the first class the application needs. Both names are now resolved to
  the real jar next to them (`liblibrary.jar`, resources included, or the `-class.jar` javac output),
  and for an ijar of an external repository's own jars - `java_import` over a downloaded
  distribution - to the file that repository shipped, found through the `_ijar` path mirror.

  Measured on a 116-project workspace: of 1842 distinct classpath entries, 1382 were ABI jars and
  all 1382 now resolve to a real jar. Each candidate is checked on disk, so an ABI jar with no
  counterpart is still passed through as aquery reported it.

  Expect one republish and reindex on the first import after upgrading.

## 0.7.1

- **The classpath now carries the real maven jars, and this - not the project layout - is what makes
  the Spring Boot Dashboard find the applications.** aquery reports what javac consumes, which for a maven dependency is bazel's header
  jar - `header_spring-boot-4.0.7.jar` sitting next to the `spring-boot-4.0.7.jar` that
  rules_jvm_external downloaded. The real jar is preferred wherever it exists: it has the class
  bodies, so navigating into a library shows code instead of an ABI stub, and its file name is the
  maven artifact name. The second part is what mattered here - the dashboard decides whether a
  project is an application by looking for a classpath jar whose name starts with `spring-boot`, so
  with header jars it found no applications anywhere, on a workspace whose projects, classpath and
  index were all fine. Lombok had this substitution already; now every dependency does, guarded by
  the file existing, so a target's own header jar is left as reported.

  Expect one republish and reindex on the first import after upgrading: the classpath entries point
  at different files.

## 0.7.0

- **New setting `bazelJava.projectLayout`, and with `repository` the Spring Tools indexer works.**
  Generated projects have always lived in the language server's own storage, with the sources linked
  in, so that nothing is written to the working copy. Parts of the java tooling assume the opposite -
  that a project's location is inside the workspace folder - and quietly skip the projects where it
  is not:
  - the Spring Tools classpath bridge computes a source folder as *project location + entry path*,
    hands its indexer a directory that does not exist, and indexes nothing at all - no symbols, no
    beans, not even handwritten ones. Measured on a 116-project workspace: 228 of 244 source entries
    pointed at directories that do not exist. This is what the setting is for;
  - jdt.ls deletes such projects at startup, which 0.6.4 already works around by claiming them with
    a build support;
  - vscode-java-debug filters its main-class search by project location too, though the Spring Boot
    Dashboard turns out not to depend on it: it asks with the project's own location rather than the
    workspace folder.

  `repository` puts each project's directory at the bazel package its targets come from. Class output
  and the relocated-sources folders are still linked into the language server's storage, so the
  working copy stays untouched. A project whose directory would contain another project's - or whose
  sources are not inside its package - keeps the old layout, and the import report says how many did.
  The default is unchanged; switching recreates the projects and costs one reindex.
- `JBazel: Doctor` reports which layout is in effect.

## 0.6.5

A follow-up to 0.6.4, from watching it run. Keeping the projects across a restart worked - workspace
initialization went from 5.3 s to 182 ms and the language server's build jobs from five minutes to
two seconds - but it exposed the next thing in the chain.

- **A restart no longer republishes every classpath container.** The guard that skips republishing an
  identical container lived only in memory, and it was seeded when JDT asked this extension to
  initialise a container. Now that the projects survive, JDT restores their containers from its own
  state and never asks - so a fresh session knew nothing and republished all of them, which makes JDT
  drop and re-index every jar behind them. The stamps are persisted with the rest of the import cache
  now, and a publish is skipped only when the stamp matches *and* JDT still holds that exact
  container.
- **`JBazel: Doctor` reports a corrupt JDT index.** This is what a mass reindex can run into: a
  language server killed mid-save leaves a half-written index file, JDT later reads a length out of it
  (`Failed to read index data ... size 1936028278`), allocates that much and dies with
  `OutOfMemoryError` - on any heap, repeatedly, with nothing wrong in the repository and no setting
  that helps. The report now names the index directory, says it is derived data, and tells you to
  delete it and reopen the window.

## 0.6.4

Startup performance. Both findings come from reading the language server's own log on a 116-project
repository, where every window reload rebuilt the workspace from nothing.

- **The generated projects survive a restart.** jdt.ls deletes any project whose location is not
  inside a workspace folder unless a build support claims it, and nothing claimed these - so all 116
  were deleted and recreated on every start, and each deletion took that project's JDT index with
  it. The reload then re-indexed the whole repository: minutes of CPU on a workspace that had not
  changed. The extension now contributes a build support that claims its own projects, and removes
  them only when the repository they came from is no longer a workspace folder.
- **A classpath build no longer re-imports the repository.** `bazelJava.buildOnImport` builds the
  classpath targets after the import, and a build that rewrote any jar forced a full discovery
  refresh: another `bazel query`, another aquery over every label, and a re-provision of every
  project - about 30 s of work after every start. A build cannot change the project layout, so the
  containers whose jars actually changed are now republished directly, and the cheap digest check
  still catches a `BUILD` or lock-file edit.

## 0.6.3

- **A settings change no longer drops the fence.** The language server does not edit its preferences
  on `didChangeConfiguration`, it rebuilds them from what the client sends - which threw away the
  output-tree exclusions the importer had written, and left the next workspace scan free to follow
  `bazel-out` again until the next import attempt. They are now re-applied from a preference-change
  listener, before the configuration change triggers anything else.
- **`--symlink_prefix` is read from the bazelrc.** Symlinks are recognised by where they point, which
  needs them to exist; the standing `bazel-*` exclusion is what covers one that a terminal build
  creates after the last import, and it only ever guessed at the name. The prefix configured in the
  rc files now gets a standing exclusion of its own. An empty prefix is refused - as a pattern it
  would exclude the whole repository - and so is `--symlink_prefix=/`, which is bazel's way of asking
  for no symlinks at all.
- `JBazel: Doctor` prints `java.import.exclusions`: the patterns the client is sending and whether
  the list is the default or one pinned in your settings. "Why does the scan still go into
  `bazel-out`" was previously answerable only from the log.

## 0.6.2

- **The labels bazel could not analyse are named.** "3 label(s) could not be analysed" reads as a
  rounding error until one of those three is the service open in the editor - where every import is
  then unresolved while the rest of the workspace is fine. The import report and the log now list
  them (up to eight, then a count), so it is obvious which projects have no classpath and why.
- Bazel's closing "Build did NOT complete successfully" no longer wins the "last error" slot in a
  failure message; the last error that names something does.

## 0.6.1

Two fixes on top of 0.6.0, both from watching it run against a repository where one external
repository cannot be fetched at all.

- **One unfetchable external repository no longer empties every classpath.** `--keep_going` makes
  bazel analyse what it can and exit non-zero for the rest, and the actions it did print are already
  on stdout - measured: ten of eleven actions emitted, exit code 1. Those were discarded along with
  the exception, so a single stale lock file left every project in the workspace without a classpath.
  A batch that fails now keeps whatever it parsed, publishes those containers, and names the labels it
  could not analyse in the import report. Only a batch that produced nothing at all is still a
  failure, and it keeps the "bazel cannot fetch a repository" classification 0.6.0 added.
- **A long bazel error keeps its tail.** The captured detail is capped, and the cap used to cut the
  end - which is exactly where bazel prints the command that fixes the problem. It is elided in the
  middle now, so both the failing rule and the remedy survive.
- The rationale in the readme, the changelog and the code comments is stated in terms of bazel and the
  language server rather than through any one repository's toolchain.

## 0.6.0

A correction. 0.4.0 and 0.5.0 told you to put `common --experimental_convenience_symlinks=ignore` in
the bazelrc and delete the `bazel-*` symlinks from the repository root. That is not this extension's
call to make: the symlinks are bazel's standard entry point into the outputs, other tooling in a
repository can depend on them, and a java importer has no business asking for them to be turned off.
The advice is withdrawn.

- **The symlinks stay, and the scan is fenced off instead.** jdt.ls looks for build files by walking
  the workspace with symlinks followed, and the same walk skips any directory whose path matches
  `java.import.exclusions`. The extension now writes the bazel output paths into that list at the
  start of every import attempt - including the attempts where it declines, which is exactly when
  jdt.ls falls through to its own gradle/maven/eclipse detection - so nothing descends into the output
  tree and nothing has to be deleted. `**/bazel-*/**` also ships as a `configurationDefaults` entry,
  alongside jdt.ls's own four patterns, for the very first session.
- **Symlinks are detected by where they point, not by their name.** `--symlink_prefix` renames all of
  them, so a root symlink is recognised by landing inside the output base. They are reported in the
  import report and in `JBazel: Doctor`, never as a fault: the doctor now only speaks up when
  `java.import.exclusions` has been pinned to a list that does not cover them, and then prints the
  exact patterns to add.
- **`--experimental_convenience_symlinks=ignore` is no longer passed on every build.** It goes only to
  builds that run in an IDE-owned output base (`bazelJava.outputBase`), where bazel would otherwise
  repoint `bazel-bin` at a tree in which only the IDE's own classpath targets were ever built. On the
  shared output base - the default - the IDE writes the same paths a terminal build does, so the flag
  is not added at all.
- **A bazel error keeps its cause.** Only lines starting with `ERROR` were captured, so a failure read
  "An error occurred during the fetch of repository 'maven_nullaway':" and stopped there - one line
  before the traceback where bazel prints the command that fixes it. The traceback and the trailing
  `Error in fail:` line are captured with it now, and a repeated final error is included too.
- **A failure that waits on a human says so.** An external repository that cannot be fetched - a
  `rules_jvm_external` lock file needing a repin, most often - fails analysis outright, so no classpath
  can be resolved and retrying changes nothing. It is classified apart from a transient failure: the
  status bar says "bazel cannot fetch a repository", the report carries the remedy, and fixing it plus
  a `MODULE.bazel` edit (or `JBazel: Refresh Classpath`) retries at once instead of counting
  anonymous failures towards a five-minute backoff.

## 0.5.0

Everything this extension contributes is now prefixed **JBazel**, so it no longer sits on top of the
official Bazel extension in the command palette. Plus the two features the manual setup this replaces
had and this did not: library sources, and a report on the configuration that makes a repository slow.

- **Commands renamed.** `JBazel: Refresh Classpath`, `JBazel: Show Import Report`,
  `JBazel: Build Classpath`. Their ids changed from `bazelJava.*` to `jbazel.*`, along with the
  language-server command ids behind them - if you bound a key to one of the old ids, rebind it.
  Settings keep the `bazelJava.` prefix, so no configuration needs changing.
- **New: `JBazel: Fetch Library Sources`.** Downloads the source jars of every third-party artifact
  and attaches them, so navigating into a library shows real source instead of decompiled bytecode.
  `rules_jvm_external` never fetches them on its own - they are inputs to no action, so
  `fetch_sources = True` alone changes nothing. Offered once when most jars turn out to have no
  sources; otherwise it only ever runs when asked. The import report now counts source attachments.
- **New: `JBazel: Doctor`.** One read-only report on what makes a repository slow, noisy or red:
  convenience symlinks in the root, vendor directories that dominate the first workspace scan, the
  heap the language server actually runs with against the number of projects, the source-attachment
  ratio, the `java.*` settings that fight the import, and the missing bazelrc lines - each with the
  line to add.
- Classpath containers now stamp each jar's source attachment as well as the jar, so sources that
  appear after the fact are picked up without reloading the window. The stamp also follows the lombok
  full-jar substitution, which it previously ignored.
- `java.import.maven.enabled` and `java.import.gradle.enabled` now default to `false`. Bazel owns
  dependency resolution here, and those importers otherwise adopt stray `pom.xml` / `build.gradle`
  files and compete for the same folders as the imported projects.
- New `bazelJava.buildJobs`, so the build this extension starts in the background does not have to
  take every core on the machine you are typing on. New `bazelJava.mavenRepository` for repositories
  whose artifacts do not live in `@maven`.
- The marketplace page documents the bazelrc worth having, and the log messages are prefixed
  `JBazel:` so they can be told apart from anything else in the language server's log.

## 0.4.0

Hardening against the branch-switch stampede: the java process no longer hangs or spins for minutes
after `git checkout`.

- Classpath containers are republished only when their content actually changed - jar list, order,
  and each jar's size and modification time. A refresh that resolves an identical classpath keeps
  the container, so JDT no longer drops and re-indexes every jar behind all of them on each branch
  switch.
- The command timeout now also covers a bazel client that is silent on stdout. Previously a client
  waiting for the server lock could hold a language-server job thread for the entire duration of a
  terminal build, and cancellation was ignored while it waited.
- The IDE's bazel invocations pass `--noblock_for_lock` (new setting `bazelJava.noblockForLock`,
  default on): when a terminal build holds the server lock the IDE fails fast, shows "waiting for
  another bazel command" in the status bar, and retries on a short fixed interval instead of
  queueing behind the build or escalating the failure backoff.
- A refresh no longer starts while git is rewriting the working tree (checkout, rebase, merge); it
  waits for the operation to finish, bounded so a stale `index.lock` cannot silence refreshes.
- A partial aquery answer - normal with `--keep_going` during a checkout or with an unreachable
  external repository - can no longer wipe populated classpaths: an empty answer for a label that
  previously had jars keeps the cached jars, and the import report counts how often that happened.
- The cache stamp is taken before discovery runs, so a second branch switch landing mid-refresh
  schedules another pass instead of silently marking stale data as current.
- Builds started by the extension pass `--experimental_convenience_symlinks=ignore`, so the IDE no
  longer creates the `bazel-bin` / `bazel-out` / `bazel-testlogs` symlinks in the repository root.
  The language server follows symlinks during its first workspace scan, and that scan runs before
  `java.project.resourceFilters` is applied, so one of those symlinks can park the Java import in the
  bazel output tree with no setting able to prevent it. Symlinks left by a build outside the IDE are
  now reported in the status bar, the import report and the log, with the bazelrc line that stops
  them coming back.
- The automatic background build defers while the bazel server is busy with someone else's command.

## 0.3.0

First public release.

Imports Bazel java targets into the Java Language Server: one `bazel query` for discovery, one
batched `bazel aquery` for the classpath, projects created in a single resource transaction, and the
whole import cached on disk so a restart costs no bazel calls.

- Nothing blocks the language server start: classpath containers are published immediately from the
  cache and filled in by a background job.
- Import scope from `bazelJava.targets`, `bazelJava.excludeTargets` or the `directories:` block of
  `.bazelproject`; a java file opened outside the scope provisions its own package on demand.
- `src/main` and `src/test` targets of one package are merged into a single project with two source
  folders.
- Failed imports back off exponentially instead of retrying in a loop; the window is shown in the
  status bar and cleared by any `BUILD` edit or by `Bazel: Refresh Classpath`.
- `bazelJava.buildOnImport` keeps the jars the classpath points at current, and republishes the
  containers only when a jar actually moved, to avoid needless reindexing.
- `bazelJava.outputBase: "ide"` gives the IDE its own bazel server, which is also then shut down
  when the language server exits.
- Files whose declared package does not match their directory are linked into the package they
  declare rather than reported as errors.
- `bazelJava.binary` and `bazelJava.outputBase` are machine-scoped and are not read from a
  repository-level configuration file, since both name something that gets executed.

If you used a hand-installed build of this extension before, the jdt.ls bundle id changed to
`io.github.sorteam.bazel.jdtls`. Remove the old copy from `~/.vscode/extensions`, then run
`Java: Clean Java Language Server Workspace` once.
