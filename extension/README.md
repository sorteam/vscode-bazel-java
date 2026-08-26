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

| Command | Effect |
|---|---|
| `Bazel: Refresh Classpath` | Drops every cache, clears the backoff window, reimports in the background |
| `Bazel: Show Import Report` | Phase timings, project and jar counts, backoff state, current scope |
| `Bazel: Build Classpath` | Runs `bazel build` over the imported targets so the jars on the classpath exist |

The status bar shows two things worth noticing: `Bazel: retry in Ns` when an import failed and is
backing off, and `Bazel: N jars not built` when the classpath points at jars that do not exist on
disk yet.

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
| `bazelJava.binary` | `""` | Path to bazel/bazelisk; `PATH` is searched when empty |

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
Run `Bazel: Build Classpath`.

**A field or method that `bazel build` accepts reads as undefined.** A jar produced before its inputs
last changed is still on disk, and JDT indexes it happily. This is what `bazelJava.buildOnImport`
exists for; it is on by default, and `Bazel: Build Classpath` forces it.

**The IDE and my terminal block each other on the bazel server.** They share one server and one lock.
Set `bazelJava.outputBase` to `ide` to give the IDE its own, at the cost of a second analysis cache
(1-2 GB). It is also the only setting under which this extension ever shuts a bazel server down -
the shared one belongs to you, not to the indexer.

**Import failed once and now nothing happens.** Failures back off exponentially, up to
`backoffMaxSeconds`, and the status bar says how long is left. Editing any `BUILD`, `.bzl` or
`MODULE.bazel` file clears the window, and so does `Bazel: Refresh Classpath`.

**Settings look ignored on the very first import.** The language server starts before this extension
does, so a scope set before the first ever import is picked up on the next reload. Changing a setting
afterwards offers to reimport straight away.

**Something is badly wrong with the java model.** `Java: Clean Java Language Server Workspace` is the
repair. The reimport that follows is served by one bazel query.

## License

MIT
