# Changelog

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
