# Changelog

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
