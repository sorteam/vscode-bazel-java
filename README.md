# Bazel Java Project Importer

A VS Code extension plus a jdt.ls (redhat.java) bundle that imports bazel `java_library` /
`java_binary` / `java_test` targets into the Java Language Server. Source folders are linked, never
copied; nothing is written into the working copy.

- [PERFORMANCE_PLAN.md](PERFORMANCE_PLAN.md) - the measurements and the plan this implements
- [INCIDENT-2026-08-25-hung-jvms.md](INCIDENT-2026-08-25-hung-jvms.md) - the failure that set the
  priorities

## Build and install

```bash
./build.sh     # javac + jar against the installed redhat.java bundles
./test.sh      # plain-main test suite (parser, grouping, settings, query)
./install.sh   # build and install into ~/.vscode/extensions
```

Then reload the VS Code window.

## How it works

| Phase | What runs |
|---|---|
| Discovery | one `bazel query kind(java_*) <scope> --output=xml --keep_going --nofetch` |
| Grouping | targets are folded into one project per package, `src/main` and `src/test` together |
| Provisioning | all Eclipse projects created in a single resource transaction, auto-build parked |
| Classpath | one `bazel aquery --query_file` over every label at once, in a background job |

Measured on a 898-package monorepo (442 java targets, 223 with sources):

| | before | after |
|---|---|---|
| discovery | 2.2 s | 1.2 s |
| classpath | 223 sequential aqueries, ~69 s, blocking | 1 batched aquery, 3.0 s, in the background |
| projects | 223 | 114 |
| restart | full replay, ~40 s of bazel | served from the on-disk cache |

## Configuration

All settings live under `bazelJava.*` in VS Code settings.

| Setting | Default | What it does |
|---|---|---|
| `bazelJava.targets` | `[]` | Target patterns to import. Empty means the whole repository, or `.bazelproject`'s `directories:`. **The biggest single win on a large monorepo.** |
| `bazelJava.excludeTargets` | `[]` | Patterns subtracted from the imported set |
| `bazelJava.useBazelProject` | `true` | Take the scope from `.bazelproject` when `targets` is empty |
| `bazelJava.importMode` | `lazy` | `lazy` also provisions a package on demand when a file outside the scope is opened; `eager` sticks to the scope |
| `bazelJava.groupSourceRoots` | `true` | Merge `src/main` and `src/test` into one project |
| `bazelJava.maxProjects` | `300` | Safety valve; above this the import is capped and warns |
| `bazelJava.buildOnImport` | `background` | Build the imported targets once per session so the jars on the classpath are current, not whatever was last produced |
| `bazelJava.discoveryNoFetch` | `true` | `--nofetch` on discovery, so indexing never reaches the network |
| `bazelJava.commandTimeoutSeconds` | `120` | Per bazel query/aquery |
| `bazelJava.backoffMaxSeconds` | `300` | Ceiling of the exponential backoff after a failed import |
| `bazelJava.outputBase` | `""` | `ide` gives the IDE its own bazel server; empty shares the developer's |
| `bazelJava.maxIdleSeconds` | `900` | `--max_idle_secs` for the IDE-owned server |
| `bazelJava.binary` | `""` | Path to bazel/bazelisk; PATH is searched when empty |

### How settings reach the server

redhat.java forwards only the `java.*` namespace to jdt.ls, and the importer runs before any
extension could push settings over `executeCommand`. Setting `process.env` from the extension does
not work either, because redhat.java is an `extensionDependency` and has already launched the server
by then - which is why `bazelJava.binary` used to do nothing.

So the extension mirrors the settings to a file the server reads at import time, and the server
merges several sources. Highest priority first:

1. `-Dbazel.<key>` system properties (via `java.jdt.ls.vmargs`)
2. `BAZEL_JAVA_<KEY>` environment variables
3. `~/.cache/bazel-java-jdtls/settings-<hash>.json` - written by the extension from `bazelJava.*`
4. `<repo>/.vscode/bazel-java.json` - hand written, shareable with the team
5. `<repo>/.bazelproject` `directories:` - import scope only

Nothing is written into the repository.

## Commands

| Command | Effect |
|---|---|
| `Bazel: Refresh Classpath` | Drops every cache, clears the backoff window, reimports in the background |
| `Bazel: Show Import Report` | Phase timings, project and jar counts, backoff state, current scope |
| `Bazel: Build Classpath` | Runs `bazel build` over the imported targets so the jars on the classpath exist |

## Notes

**Stale jars.** The counterpart to the missing-jar problem, and the nastier one. `aquery` reports
what a build *would* consume; a jar produced before its inputs last changed is still sitting on disk
and JDT indexes it happily. The symptom is a method or field that `bazel build` compiles fine but
the IDE calls undefined - especially for code generated from a spec (openapi, protobuf), where the
whole class comes from a `-gensrc.jar`. `bazelJava.buildOnImport` builds the imported targets once
per session to keep them current; `Bazel: Build Classpath` does it on demand.

**Index churn and OutOfMemoryError.** Republishing a classpath container makes JDT forget what it
read from every jar behind it and index them again. On this repository that is ~1.6k jars and over a
gigabyte written under `.metadata/.plugins/org.eclipse.jdt.core`. Doing it on every start is not
merely slow: an editor closed mid-write leaves truncated index files behind, and JDT later reads a
length field out of one of them as garbage - `Failed to read index data ... size 1885434739` - and
dies with `OutOfMemoryError` no matter how large `-Xmx` is. So `buildOnImport` fingerprints the
classpath jars by path, size and mtime around the build and republishes only when something actually
moved; an up-to-date repository now costs one bazel no-op and no reindex at all. If a workspace has
already been corrupted this way, `Java: Clean Java Language Server Workspace` is the repair - with
this plugin the reimport that follows takes a couple of seconds.

**Source roots.** Eclipse insists that a file's package match its directory below the source root;
bazel has no such rule, because javac is handed an explicit list of files. That makes the source
root worth deriving from what the files declare rather than from the shape of the path: a target
whose sources sit under `src/main/java/com/github/...` has its root at `src/main/java`, not at the
first path segment `src`. The winner has to cover at least half the files read, and a package that
would put the root at the repository root is refused. Where roots nest - two targets in the same
project, one under the other - the outer folder excludes the inner one, otherwise it claims the
inner one's files under the wrong package and duplicates every type in it.

Files whose package matches no directory at all are put where their own package says they belong:
excluded from the real source folder by a resource filter and linked into a second one at the path
the package implies. Nothing on disk moves. This matters more than the error it removes - a file
placed in the wrong package also breaks every unqualified reference from it to its real neighbours,
so one misplaced file costs a handful of errors in files that are themselves fine.

A classpath exclusion alone is not enough, and is worse than nothing: the resource survives, the
editor opens the file by its path on disk, and the language server answers "not on the classpath of
project X, only syntax errors are reported". Hence the resource filter, which removes the resource
outright.

**Generated code.** A target's own output is never on its own compile classpath - bazel has no
reason to put it there - but the IDE needs it, because that jar is the only place the annotation
processors' output exists: the JPA static metamodel (`Entity_`), whole openapi-generated APIs, and
anything else written during compilation. The Javac action's `--output` is therefore added to its
own label's jars, ahead of everything it compiles against; types the project also has sources for
still resolve from the source folder, which JDT reaches first. The matching `-gensrc.jar` is
attached as that entry's source, so `Entity_` opens in the generated source rather than a decompiled
class.

**Lombok.** vscode-java enables lombok by finding a `lombok-<version>.jar` on the project classpath
and loading that exact file with `-javaagent`. Bazel puts lombok on the *processor* path and only
its interface jar (`header_lombok-*.jar`) on the classpath - right name, no bytecode. The container
substitutes the full jar written next to it. Whether the agent then actually attaches is
vscode-java's decision, not this plugin's; if lombok members still read as undefined, put
`-javaagent:<path to redhat.java>/lombok/lombok-*.jar` in `java.jdt.ls.vmargs` directly.

**Missing jars.** `aquery` reports the jars a Javac action *would* consume, not jars that exist.
With `--nojava_header_compilation` those are full compile outputs, so on a fresh clone most of the
classpath does not exist yet and is dropped. The count is logged and shown in the status bar; `Bazel:
Build Classpath` materialises them.

**Backoff.** A failed import is not retried immediately. The window doubles from 2 s up to
`backoffMaxSeconds` and is shown in the status bar. It is cleared by any success, by a change to a
`BUILD` / `*.bzl` / `MODULE.bazel` file, and by `Bazel: Refresh Classpath`.

**Exit code 3.** `--keep_going` returns 3 when some package fails to load - a broken `BUILD`
somewhere in the repository, or an unreachable registry. The java targets still come back, so exit 3
is accepted and the loading errors are logged once and then counted.

**`.classpath` is load-bearing.** jdt.ls checks every java project for a `.classpath` file on disk
and, when it is missing, logs `project has no .classpath. Removing Java nature and builder` and does
exactly that. So `setRawClasspath` is called in the form that writes the file, even though the
classpath is also set in memory. Do not "optimise" that away.

**A separate output base.** With `bazelJava.outputBase: "ide"` the IDE gets its own bazel server, so
a `bazel build` in a terminal no longer blocks indexing and vice versa, and the server is shut down
when the language server exits. It costs a second analysis cache (1-2 GB), so it is opt-in. Without
it, no bazel server is ever shut down by this extension - the shared one belongs to the developer.
