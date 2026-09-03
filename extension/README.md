# Bazel Java Project Importer

Imports Bazel `java_library` / `java_binary` / `java_test` targets into the Java Language Server, so
that code completion, go-to-definition and diagnostics work in a Bazel repository without a Maven or
Gradle project anywhere.

Source folders are **linked**, never copied, and nothing is written into your working copy.

## Requirements

- [Language Support for Java by Red Hat](https://marketplace.visualstudio.com/items?itemName=redhat.java)
  (installed automatically as a dependency) - the importer runs inside its language server
- `bazel` or `bazelisk` on `PATH`, or [`bazelJava.binary`](#settings) pointing at it
- A repository with `MODULE.bazel`, `WORKSPACE.bazel`, `WORKSPACE` or `REPO.bazel` at its root
Nothing needs to be done about the `bazel-*` convenience symlinks in the repository root, and
nothing needs to be removed: they are bazel's own, other tooling in a repository may resolve build
outputs through them, and this extension does not read them - output paths come from `bazel info`. The
language server looks for build files by walking the workspace with symlinks followed, which is why an
unfenced `bazel-out` used to park the import in the action output tree; the extension adds those paths
to `java.import.exclusions` on every import instead, so the scan skips them, and puts them back when a
settings change rebuilds that list. A `--symlink_prefix` in your bazelrc is read and covered too. Other
large directories still matter - `node_modules` and similar - and `JBazel: Doctor` reports them.

## Getting started

Open the repository and wait for "Workspace initialized". That is all, on a small repository.

On a large monorepo, set the scope first - it is by far the biggest difference between "usable" and
"the IDE is busy for a minute":

```jsonc
// .vscode/settings.json
{
  "bazelJava.targets": ["//services/checkout/...", "//platform/common/..."]
}
```

With a scope set, opening a java file outside it provisions just that one package on demand, so
following a definition into another service still works.

If the repository already has a `.bazelproject` (the IntelliJ Bazel plugin's project view), its
`directories:` block is used as the scope automatically.

## Commands

Everything this extension contributes is prefixed **JBazel**, so it stays clear of the official
Bazel extension's own `Bazel: ...` commands in the palette.

| Command | Effect |
|---|---|
| `JBazel: Refresh Classpath` | Drops every cache, clears the backoff window, reimports in the background |
| `JBazel: Show Import Report` | Phase timings, project and jar counts, source attachments, backoff state, scope |
| `JBazel: Build Classpath` | Runs `bazel build` over the imported targets so the jars on the classpath exist |
| `JBazel: Fetch Library Sources` | Downloads the source jars of third-party artifacts, so navigating into a library shows real source |
| `JBazel: Doctor` | One report on the things that make a repository slow, noisy or red, and the exact line to fix each |

The status bar shows what is worth noticing: `JBazel: retry in Ns` when an import failed and is
backing off, `JBazel: N jars not built` when the classpath points at jars that do not exist on disk
yet, and a warning about `bazel-*` symlinks in the repository root, which can hang the import
outright.

## Library sources and javadoc

Out of the box, Ctrl+Click into a third-party type lands in decompiled bytecode: no parameter names,
no javadoc, no comments. That is not a limitation of this extension but of how the dependencies are
fetched - `rules_jvm_external` downloads source jars lazily, and since they are inputs to no build
action, nothing ever pulls them. `fetch_sources = True` alone does not change that.

Run **JBazel: Fetch Library Sources** once. It asks bazel for every sources artifact and builds them,
then attaches each `-sources.jar` to its jar and republishes only the projects that actually gained
sources. Expect a large download - a couple of gigabytes on a repository with ~840 artifacts - which
is why it is a command you run and never something that happens by itself. Artifacts that publish no
sources at all (18 of 840 on the repository this was measured against) stay as bytecode; a
decompiler extension is a reasonable fallback for those.

The import report counts the result, so `source attachments : 263 of 289` is the answer to "why does
this one open as bytecode".

## Settings

| Setting | Default | What it does |
|---|---|---|
| `bazelJava.targets` | `[]` | Target patterns to import. Empty means the whole repository, or `.bazelproject`'s `directories:` |
| `bazelJava.excludeTargets` | `[]` | Patterns subtracted from the imported set |
| `bazelJava.useBazelProject` | `true` | Take the scope from `.bazelproject` when `targets` is empty |
| `bazelJava.importMode` | `lazy` | `lazy` also provisions a package on demand when a file outside the scope is opened; `eager` sticks to the scope |
| `bazelJava.groupSourceRoots` | `true` | Merge the `src/main` and `src/test` targets of a package into one project |
| `bazelJava.maxProjects` | `300` | Safety valve; above this the import is capped and warns |
| `bazelJava.buildOnImport` | `background` | Build the imported targets once per session, so the jars on the classpath are current |
| `bazelJava.discoveryNoFetch` | `true` | `--nofetch` on discovery, so indexing never reaches the network |
| `bazelJava.commandTimeoutSeconds` | `120` | Per bazel query/aquery |
| `bazelJava.backoffMaxSeconds` | `300` | Ceiling of the exponential backoff after a failed import |
| `bazelJava.outputBase` | `""` | `ide` gives the IDE its own bazel server; empty shares the one your terminal uses |
| `bazelJava.maxIdleSeconds` | `900` | `--max_idle_secs` for the IDE-owned server |
| `bazelJava.buildJobs` | `0` | `--jobs` for the builds this extension starts by itself. `0` is bazel's default, every core |
| `bazelJava.mavenRepository` | `maven` | External repository holding the third-party artifacts, used to find source jars |
| `bazelJava.binary` | `""` | Path to bazel/bazelisk; `PATH` is searched when empty |

Two `java.*` defaults are set for you, and both are conflict avoidance rather than taste:
`java.import.maven.enabled` and `java.import.gradle.enabled` are `false`, because bazel owns
dependency resolution here and those importers otherwise adopt any stray `pom.xml` or
`build.gradle` and compete for the same folders as the imported projects. Override them if your
repository genuinely has a Maven build alongside bazel.

`bazelJava.binary` and `bazelJava.outputBase` are machine-scoped: they name something that gets
executed, so a repository cannot set them for you.

Everything except those two can also be committed for the whole team in
`<repo>/.vscode/bazel-java.json`, using the same keys without the `bazelJava.` prefix. Machine-level
overrides are `-Dbazel.<key>` (through `java.jdt.ls.vmargs`) and `BAZEL_JAVA_<KEY>` in the
environment.

## How it works

| Phase | What runs |
|---|---|
| Discovery | one `bazel query kind(java_*) <scope> --output=xml --keep_going --nofetch` |
| Grouping | targets are folded into one project per package, `src/main` and `src/test` together |
| Provisioning | all Eclipse projects created in a single resource transaction, auto-build parked |
| Classpath | one `bazel aquery --query_file` over every label at once, in a background job |

Two decisions are worth knowing about, because they are what makes a restart cheap:

**Nothing blocks on bazel.** The classpath containers are published empty and filled in behind
"Workspace initialized". A repository-wide `aquery` runs once, in one batch, in a background job.

**The import is cached on disk.** A restart is served from the cache and costs no bazel calls at
all; a background job then checks whether any `BUILD` file moved and re-imports only if it did.

## The bazelrc worth having

None of these is required - they are the difference between a monorepo that feels fine and one that
does not. Put them in a personal, gitignored bazelrc (`.bazelrc.user`, `~/.bazelrc`, or whatever
layer your repository already uses).

```bash
# Leave a couple of cores free, so a build does not starve the editor. bazelJava.buildJobs does the
# same for the builds this extension starts on its own.
build --jobs=8

# Let an idle bazel server release its JVM heap instead of holding it next to the language server's.
startup --max_idle_secs=600

# A local cache with a ceiling, so a branch switch reuses outputs instead of rebuilding them.
common --disk_cache=~/.cache/bazel-disk
common --experimental_disk_cache_gc_max_size=50G
```

`JBazel: Doctor` checks all of these against what it can find, and prints the missing line rather
than editing anything.

## Known limitations

Honest list, because they decide whether this works on *your* repository:

- **Layout.** Main/test grouping recognises the `src/main` and `src/test` convention. On a repository
  laid out the Bazel way (`java/` plus `javatests/`) grouping does nothing and you get one project
  per target. Test targets are recognised by their path or by the target name (`test`, `tests`,
  `junit`).
- **Rule kinds.** Only `java_library`, `java_binary` and `java_test`. Kotlin, Scala and
  `java_proto_library` targets are not imported.
- **Generated sources.** A target whose `srcs` are produced by another rule (`srcs = [":generated"]`)
  is skipped, since discovery reads `srcs` for the source root. Its jars still appear on the
  classpath of targets that depend on it.
- **Windows** is untested. macOS and Linux are what this has run on.

## Troubleshooting

**Everything is unresolved and the report shows jars missing on disk.** `aquery` reports the jars a
build *would* consume, not jars that exist - on a fresh clone most of them have never been produced.
Run `JBazel: Build Classpath`.

**A field or method that `bazel build` accepts reads as undefined.** A jar produced before its inputs
last changed is still on disk, and JDT indexes it happily. This is what `bazelJava.buildOnImport`
exists for; it is on by default, and `JBazel: Build Classpath` forces it.

**The IDE and my terminal block each other on the bazel server.** They share one server and one lock.
Set `bazelJava.outputBase` to `ide` to give the IDE its own, at the cost of a second analysis cache
(1-2 GB). It is also the only setting under which this extension ever shuts a bazel server down -
the shared one belongs to you, not to the indexer.

**Import failed once and now nothing happens.** Failures back off exponentially, up to
`backoffMaxSeconds`, and the status bar says how long is left. Editing any `BUILD`, `.bzl` or
`MODULE.bazel` file clears the window, and so does `JBazel: Refresh Classpath`.

**Settings look ignored on the very first import.** The language server starts before this extension
does, so a scope set before the first ever import is picked up on the next reload. Changing a setting
afterwards offers to reimport straight away.

**The import never finishes.** Check `JBazel: Doctor` for a directory the first workspace scan has to
walk. The `bazel-*` symlinks are excluded automatically; a pinned `java.import.exclusions` in your own
settings replaces that list rather than extending it, and the doctor prints both what the client is
sending and the exact patterns to add back. Vendor directories inside the workspace (`node_modules`,
`.venv`, `vendor`) are the other usual answer.

**The status bar says bazel cannot fetch a repository.** Analysis cannot run, so no classpath can be
resolved - typically a `rules_jvm_external` lock file that needs repinning after `MODULE.bazel`
changed. The full bazel error, including the command it suggests, is in `JBazel: Show Import Report`.
Fixing it and editing `MODULE.bazel` (or running `JBazel: Refresh Classpath`) retries at once, without
waiting out a backoff.

**Ctrl+Click into a library opens decompiled bytecode.** The source jars were never downloaded; that
is the default state of a `rules_jvm_external` repository. Run `JBazel: Fetch Library Sources`, see
[Library sources and javadoc](#library-sources-and-javadoc).

**Everything is slow, noisy or red and it is not obvious why.** Run `JBazel: Doctor`. It reports the
symlinks in the repository root, vendor directories that dominate the first workspace scan, the heap
the language server actually runs with against the number of projects, the source-attachment ratio,
the `java.*` settings that fight the import, and which bazelrc lines are missing. It only reads.

**Something is badly wrong with the java model.** `Java: Clean Java Language Server Workspace` is the
repair. The reimport that follows is served by one bazel query.

## License

MIT
