# Bazel Java Project Importer

A VS Code extension plus a jdt.ls (redhat.java) bundle that imports bazel `java_library` /
`java_binary` / `java_test` targets into the Java Language Server. Source folders are linked, never
copied; nothing is written into the working copy.

This file is for people working on the extension. The user-facing documentation - what it does, every
setting, troubleshooting - is the marketplace listing, [extension/README.md](extension/README.md).

Published as `belfegor.vscode-bazel-java`.

## Layout

| Path | What it is |
|---|---|
| [extension/](extension/) | Everything that goes into the vsix: the JS half, the manifest, the marketplace page |
| [extension/extension.js](extension/extension.js) | Settings mirror, commands, status bar, BUILD file watcher |
| [server/src/](server/src/io/github/sorteam/bazel/jdtls/) | The OSGi bundle that runs inside jdt.ls |
| [server/plugin.xml](server/plugin.xml) | Extension points: project importer, classpath container, command handler |
| [server/test/](server/test/io/github/sorteam/bazel/jdtls/PluginTests.java) | Plain-main tests for the classes that avoid the Eclipse runtime |
| [assets/](assets/) | Icon source and its renderer |
| `out/`, `dist/`, `extension/server/`, `extension/LICENSE` | Generated, not in git |

## Build, test, package

```bash
./build.sh     # javac + jar against the installed redhat.java bundles, staged into extension/server/
./test.sh      # plain-main test suite (parser, grouping, settings, query)
./package.sh   # build + vsce package -> dist/vscode-bazel-java-<version>.vsix
./install.sh   # package, then install the vsix through the code CLI
```

Then reload the VS Code window.

The build is javac and jar - no Maven, no Tycho, no target platform. The classpath comes from the
jars inside an installed redhat.java, so there is a real dependency on which version that is:
`REDHAT_JAVA` pins it, and by default the newest one found under `~/.vscode/extensions` wins. The
bundle compiles against jdt.ls internals, so that choice decides which API it is built against -
worth making explicit before this is built anywhere but a laptop.

`JAVA_HOME` picks the JDK (17 or newer). The version lives in
[extension/package.json](extension/package.json) alone; `build.sh` substitutes it into
`Bundle-Version`, so the manifest cannot drift. The icon is generated -
`python3 assets/render-icon.py` after editing [assets/icon.svg](assets/icon.svg).

Releasing: bump the version, add a [changelog](extension/CHANGELOG.md) entry, then
`npx @vscode/vsce publish -p <token>` from `extension/`.

## How it works

| Phase | What runs |
|---|---|
| Discovery | one `bazel query kind(java_*) <scope> --output=xml --keep_going --nofetch` |
| Grouping | targets are folded into one project per package, `src/main` and `src/test` together |
| Provisioning | all Eclipse projects created in a single resource transaction, auto-build parked |
| Classpath | one `bazel aquery --query_file` over every label at once, in a background job |

Measured by a headless jdt.ls run - the same `initializationOptions.bundles` redhat.java uses - on a
898-package monorepo (442 java targets, 223 with sources), bazel 9.2.0, redhat.java 1.55.0, bazel
server warm:

| Phase | Cold jdt.ls | Restart |
|---|---|---|
| discovery (`bazel query`) | 1012 ms | 18 ms, from cache, bazel not called |
| container initialization | 0 bazel calls | 0 bazel calls |
| provisioning | 942 ms, 114 projects | 1020 ms |
| classpath resolution | 2 batched aqueries, ~3.2 s, in the background | 0 bazel calls |
| **`Workspace initialized`** | **2105 ms** | **1255 ms** |
| cache validity check | - | 341 ms, walking the BUILD files |

The batch is what makes classpath resolution cheap: one `aquery --query_file set(223)` is 3.0 s,
where a per-target `aquery mnemonic("Javac", <label>)` costs ~310 ms and 223 of them are ~69 s.
Naming the labels explicitly rather than querying `//...` matters for more than the 4x - `//...`
pulls every js, oci and helm target into analysis, so one unreachable external repository breaks a
query that has no business touching it.

The two decisions behind those numbers: nothing on the indexing path blocks on bazel (containers are
published from cache and filled in by
[ClasspathResolveJob](server/src/io/github/sorteam/bazel/jdtls/ClasspathResolveJob.java)), and the
whole import is persisted by
[ClasspathStore](server/src/io/github/sorteam/bazel/jdtls/ClasspathStore.java) so a restart costs no
bazel calls at all.

## How settings reach the server

redhat.java forwards only the `java.*` namespace to jdt.ls, and the importer runs before any
extension could push settings over `executeCommand`. Setting `process.env` from the extension does
not work either, because redhat.java is an `extensionDependency` and has already launched the server
by then - which is why `bazelJava.binary` used to do nothing.

So the extension mirrors the settings to a file the server reads at import time, and
[BazelSettings](server/src/io/github/sorteam/bazel/jdtls/BazelSettings.java) merges several sources.
Highest priority first:

1. `-Dbazel.<key>` system properties (via `java.jdt.ls.vmargs`)
2. `BAZEL_JAVA_<KEY>` environment variables
3. `~/.cache/bazel-java-jdtls/settings-<hash>.json` - written by the extension from `bazelJava.*`
4. `<repo>/.vscode/bazel-java.json` - hand written, shareable with the team
5. `<repo>/.bazelproject` `directories:` - import scope only

`binary` and `outputBase` are read from 1-3 only. Both name something that gets executed - one goes
straight to `ProcessBuilder`, the other becomes a `--output_base` startup option - so a repository
must not be able to set them: cloning someone's repository and opening it would otherwise run
whatever binary that repository named. They are machine-scoped in the manifest for the same reason.

Nothing is written into the repository.

Two consequences worth knowing. The hash in the file name is computed independently on both sides
(`sha256(fsPath)` in JS, `sha256(getAbsolutePath())` in Java); if those strings ever differ the
bridge silently goes unfound and the server runs on defaults. And because the language server starts
first, settings changed before the very first import land only on the next reload.

## Invariants

Things that look like dead weight and are not. Each one cost a debugging session.

**`.classpath` is load-bearing.** jdt.ls checks every java project for a `.classpath` file on disk
and, when it is missing, logs `project has no .classpath. Removing Java nature and builder` and does
exactly that. So `setRawClasspath` is called in the form that writes the file, even though the
classpath is also set in memory. Do not "optimise" that away.

**The java nature is applied after `open()`, not handed to `create()`.**
`IProject.create(description, ...)` persists neither the natures nor the build spec, so a project
directory left behind by a previous session comes back with an empty `<natures>` block, and
`JavaProject.exists()` - which checks exactly that nature - then fails every `setRawClasspath` with
"does not exist".

**Provisioning is two transactions, not one.** A project created inside a batch is not visible to the
java model until that batch ends and the delta has been broadcast, so configuring it in the same
transaction fails. Creation is its own transaction; configuration runs in a second one.

**Containers are republished only when their content actually changed.** Republishing makes JDT
forget what it read from every jar behind the container and index them again - on a large repository
~1.6k jars and over a gigabyte under `.metadata/.plugins/org.eclipse.jdt.core`. That is not merely
slow: an editor closed mid-write leaves truncated index files behind, and JDT later reads a length
field out of one of them as garbage (`Failed to read index data ... size 1885434739`) and dies with
`OutOfMemoryError` regardless of `-Xmx`. The guard sits at the publish site:
[ClasspathResolveJob](server/src/io/github/sorteam/bazel/jdtls/ClasspathResolveJob.java) compares a
[ContainerStamp](server/src/io/github/sorteam/bazel/jdtls/ContainerStamp.java) - jar list, order,
size, mtime - against what was last handed to JDT (seeded by the container initializer from the disk
cache) and skips `setClasspathContainer` on a match. This is what keeps a branch switch, which
re-resolves every project, from re-indexing the whole repository.
[BuildClasspathJob](server/src/io/github/sorteam/bazel/jdtls/BuildClasspathJob.java) additionally
fingerprints the jars around its build so an up-to-date build does not even trigger a refresh.

**The bazel output tree is fenced off from the language server's scan, and the symlinks are left
alone.** jdt.ls finds build files with `BasicFileDetector`, which walks the workspace as
`Files.walkFileTree(root, EnumSet.of(FOLLOW_LINKS), ...)`, so one `bazel-out` in the root used to send
the import into the whole action output tree. The same detector seeds its exclusions from
`Preferences.getJavaImportExclusions()` and returns `SKIP_SUBTREE` on a match, so
[ImportExclusions](server/src/io/github/sorteam/bazel/jdtls/ImportExclusions.java) writes the output
paths there from
[BazelProjectImporter.applies](server/src/io/github/sorteam/bazel/jdtls/BazelProjectImporter.java) -
which runs at importer order 150, ahead of gradle (300), maven (400), eclipse (1000) and
invisible-project (1500) detection, and on the path where this importer *declines* as well, since that
is when jdt.ls falls through to those. The symlinks themselves are the developer's and the rest of the
repository's - other tooling may resolve outputs through them - so they are detected by target rather
than by name (`--symlink_prefix` renames them) and reported, never removed. Detection by target needs
the symlink to exist, so a standing `<root>/bazel-*` pattern covers one a terminal build creates later,
widened by whatever `--symlink_prefix` the bazelrc sets
([BazelRc](server/src/io/github/sorteam/bazel/jdtls/BazelRc.java)). Preferences are rebuilt rather than
edited on `didChangeConfiguration`, which used to drop the patterns until the next import, so
[ScanFence](server/src/io/github/sorteam/bazel/jdtls/ScanFence.java) re-applies them from a
preference-change listener. `--experimental_convenience_symlinks=ignore` goes only on builds that run in
an IDE-owned output base, where bazel would otherwise repoint `bazel-bin` at outputs only the IDE
built.

**The command timeout covers a silent process.** `waitFor(timeout)` only runs after stdout hits EOF,
and a bazel client waiting for the server lock writes nothing to stdout - so the timeout used to
never fire in exactly the case it existed for, and a terminal build could hold a jdt.ls job thread
(plus the command lock behind it) for its whole duration. A watchdog now kills the process at the
deadline whatever it is doing, and doubles as the only cancellation check that works while the
process is silent.

**A busy server is not a failure.** IDE invocations pass `--noblock_for_lock` (startup option,
verified on bazel 9.2: exit 9, does not restart a running server), and "Another command (pid=...)"
on stderr is recognised. Both surface as a distinct busy state: a short fixed retry window in the
[FailureGate](server/src/io/github/sorteam/bazel/jdtls/FailureGate.java) that never escalates the
exponential backoff, and a status bar entry saying the IDE is waiting - instead of an IDE that
silently looks hung.

**Refreshes wait for git.** [DiscoveryRefreshJob](server/src/io/github/sorteam/bazel/jdtls/DiscoveryRefreshJob.java)
reschedules while `index.lock` / `MERGE_HEAD` / a rebase directory exists
([GitState](server/src/io/github/sorteam/bazel/jdtls/GitState.java)), bounded at five minutes so a
crashed git cannot silence refreshes. Refreshing against a half-checked-out tree imports a mix of
two branches and prunes projects that come back seconds later. For the same reason the cache stamp
is the BUILD-file digest taken *before* discovery: if the tree moved mid-refresh, the data is saved
unstamped and another pass runs.

**A partial aquery must not empty a populated classpath.** With `--keep_going` a label whose package
failed to load simply has no Javac action in the output. Storing that as "empty classpath" floods
the workspace with false errors and forces a full rebuild twice. A label that genuinely lost its
sources disappears from discovery instead, so an empty answer for a previously populated label keeps
the cached jars ([BazelClasspathCache](server/src/io/github/sorteam/bazel/jdtls/BazelClasspathCache.java)).

**Exit code 3 is success.** `--keep_going` returns 3 when some package fails to load - a broken
`BUILD` somewhere, or an unreachable registry. The java targets still come back, so 3 is accepted and
the loading errors are logged once and then counted. Treating it as failure once turned a transient
network outage into an overnight retry loop.

**Backoff lives in `applies()`, not in the import.** jdt.ls asks the importer whether it applies and,
if it says yes and then throws, asks again on the next trigger. Declining to apply is what keeps it
out of `importToWorkspace()` entirely while the window is open.

**Logging is deduplicated.** The same block of bazel errors written on every retry rotated the jdt.ls
log every 42 minutes and destroyed the rest of the server's history. See
[BazelLog](server/src/io/github/sorteam/bazel/jdtls/BazelLog.java).

**Only a bazel server this plugin started is ever shut down.** The shared one belongs to the
developer's terminal, and killing it would cancel their build. That is also why a dedicated
`outputBase` is opt-in.

## Naming

Everything user-facing is prefixed **JBazel**: the command palette entries (`category` in the
manifest), the VS Code command ids (`jbazel.*`), the jdt.ls delegate command ids, the status bar, the
output channel and every log line. Two reasons, and the second is the load-bearing one: the official
Bazel extension owns the `Bazel: ...` block in the palette, and jdt.ls delegate command ids live in
one namespace shared by every bundle in the language server - `bazel.status` is exactly what a second
bazel plugin would register. Settings keep the `bazelJava.` prefix: they collide with nothing (the
official extension uses `bazel.*`), and renaming them would silently drop whatever users had set.

## Design notes

**Source roots are derived from what files declare, not from the path.** Eclipse insists that a
file's package match its directory below the source root; bazel has no such rule, because javac is
handed an explicit list of files. A target whose sources sit under `src/main/java/com/github/...` has
its root at `src/main/java`, not at the first path segment `src`. The winner has to cover at least
half the files read, and a package that would put the root at the repository root is refused. Where
roots nest, the outer folder excludes the inner one, otherwise it claims the inner one's files under
the wrong package and duplicates every type in it.

Files whose package matches no directory at all are put where their own package says they belong:
excluded from the real source folder by a resource filter and linked into a second one at the path
the package implies. Nothing on disk moves. A classpath exclusion alone would be worse than nothing -
the resource survives, the editor opens the file by its path on disk, and the language server answers
"not on the classpath of project X, only syntax errors are reported". Hence the filter, which removes
the resource outright. See
[SourceRelocation](server/src/io/github/sorteam/bazel/jdtls/SourceRelocation.java).

**A target's own output jar goes on its own classpath.** Bazel never puts it there - a target does
not depend on itself - but that jar is the only place the annotation processors' output exists: the
JPA static metamodel (`Entity_`), whole openapi-generated APIs, anything written during compilation.
Types the project also has sources for still resolve from the source folder, which JDT reaches first.
The matching `-gensrc.jar` is attached as that entry's source, so `Entity_` opens in generated source
rather than a decompiled class.

**Source jars are fetched by name, or not at all.** `rules_jvm_external` supports
`fetch_sources = True`, but the source jars are inputs to no action, so a normal build never
downloads them and `sourcesFor()` finds nothing on disk - which reads as "this extension cannot show
sources". [FetchSourcesJob](server/src/io/github/sorteam/bazel/jdtls/FetchSourcesJob.java) lists the
artifact repository and builds every label whose name contains `sources`. Matching on the name rather
than the rule kind is deliberate: the target naming has changed between rules_jvm_external versions
and between its bzlmod and WORKSPACE paths, while `sources` has stayed. The repository name is a
setting (`bazelJava.mavenRepository`) because nothing else in the plugin needs to know it exists.

**The container stamp covers source attachments.** Fetching sources changes no jar - same paths, same
sizes, same mtimes - so a stamp over jars alone answers "nothing changed", nothing is republished, and
the sources stay invisible until the window is reloaded. [ContainerStamp](server/src/io/github/sorteam/bazel/jdtls/ContainerStamp.java)
therefore resolves each entry exactly as the container does, lombok substitution included, and mixes
in the attachment it lands on. That substitution used to be missing from the stamp entirely.

**The doctor measures the heap from the JVM, not from the setting.** Only a full window reload applies
a changed `-Xmx`, so `java.jdt.ls.vmargs` and the heap the server is actually running with routinely
disagree - and that disagreement is itself the answer to "I raised the heap and nothing happened".
`Runtime.maxMemory()` cannot be wrong about it. The `java.*` half of the report comes from the
extension instead, because reading jdt.ls preferences from inside a bundle means internal APIs that
break on the next redhat.java release.

**The doctor only measures vendor directories.** The first version flagged any large directory below
the workspace root, which on a monorepo means `services/` - advice nobody can act on. What can be
acted on is a `node_modules` inside the workspace: 4.4 GB and 151k files on the repository this was
built for, walked by the same pre-filter scan that follows the bazel symlinks, and holding no java at
all. The list of names is in [Doctor](server/src/io/github/sorteam/bazel/jdtls/Doctor.java).

**Lombok gets the full jar, not the interface jar.** vscode-java enables lombok by finding a
`lombok-<version>.jar` on the project classpath and loading that exact file with `-javaagent`. Bazel
puts lombok on the *processor* path and only `header_lombok-*.jar` on the classpath - right name, no
bytecode - so the container substitutes the full jar written next to it. Only lombok is treated this
way; preferring full jars everywhere would grow the index for no benefit.

**Missing jars are counted, not hidden.** `aquery` reports the jars a Javac action *would* consume,
not jars that exist. Where a repository disables header compilation these are full compile outputs,
so on a fresh clone most of the classpath has never been produced. Dropping them silently is what
makes a fresh clone look like a project with no dependencies, so the count is logged and surfaced in
the status bar, and `bazelJava.buildOnImport` materialises them.

**Discovery is offline first.** IDE indexing has no business fetching image manifests from a
container registry, so `--nofetch` is tried first and only falls back to a fetching run when it
produces nothing.

## Known gaps

Kept honest, since they decide whether this works on a given repository: main/test grouping
recognises only the `src/main` / `src/test` convention; the rule kinds are fixed at `java_library`,
`java_binary` and `java_test`; a target whose `srcs` are generated by another rule is skipped; and
Windows is untested.
