# JReverse engine

The JVM process that runs the decompilers. The Tauri app starts it with a
bundled Java runtime and talks to it over stdin/stdout; see
`src-tauri/src/engine/` for the client.

## Build

Needs JDK 21 to build (users need no Java; the runtime ships with the app).

| Command (from repo root) | What it does |
|---|---|
| `pnpm engine` | Fat jar + jlink runtime into `src-tauri/engine-dist/`. Runs before `tauri dev` / `tauri build`. |
| `pnpm engine:test` | Unit and protocol tests, then the smoke test on the shipped runtime. |

The runtime's modules come from `jdeps --list-deps`, plus `extraModules`
(reached only by reflection) minus `excludedModules` (unused at runtime). If
the smoke test fails with `NoClassDefFoundError`, a module is missing: adjust
those lists in `build.gradle.kts`.

## Protocol (version 2)

One compact JSON object per line. stdout carries protocol messages only;
logs go to stderr. The engine exits on `shutdown` or when stdin closes.

```jsonc
← {"method":"ready","params":{"protocol":2,"version":"0.1.0","engines":["jadx","vineflower"]}}
→ {"id":1,"method":"open","params":{"path":"/x/app.apk"}}
← {"id":1,"result":{"session":"s1","kind":"apk","classCount":4812,"engines":["jadx"],"ms":1339}}
→ {"id":2,"method":"decompile","params":{"session":"s1","class":"a/b"}}
← {"id":2,"error":{"code":"NO_CLASS","message":"class not found: a/b"}}
← {"method":"progress","params":{"ticket":"t1","done":38,"total":146}}
```

| Method | Params | Result |
|---|---|---|
| `open` | `path`, `deobfuscate`? | `session`, `kind` (`apk` `aab` `aar` `jar` `dex` `class`), `classCount`, `engines`, `deobfuscated`, `ms` |
| `listClasses` | `session` | `[{id, kind, name?}]`, top-level classes sorted by id; `name` when jadx shows another name (a rename, a generated alias) |
| `decompile` | `session`, `class`, `engine`? (default `jadx`) | `source`, `engine`, `ms`, `warnings`, `links`, `decls`, `nodes` |

**Deobfuscation.** `deobfuscate: true` has jadx give names shorter than 3
or longer than 64 characters, and clashing ones, generated aliases such as
`C0123a`. Ids stay the original names. No mapping file is read or written.

**Decompilers.** `engines` in `open` lists the ones that can read the file:
jadx always, Vineflower for JVM class files (JAR, AAR, a single class; not
DEX). Vineflower loads on first use and has no `links`; renames and comments
apply to jadx only. Both share jadx's class ids.
| `smali` | `session`, `class` | `source` (smali for DEX, JVM bytecode for class files), `ms` |
| `node` | `session`, `node` | a node (below) |
| `usages` | `session`, `node` | `usages: [{cls, line, col, len, text, in?}]`, `ms` |
| `search` | `session`, `query`, `regex`?, `caseSensitive`?, `scopes`?, `limit`? (1000), `ticket`? | `hits`, `truncated`, `searched`, `ms` |
| `strings` | `session`, `ticket`? | `strings: [{value, uses, at}]`, `ms`: every string constant in the code (and constant field values), `at` naming up to 12 of the methods and fields that use it. Read from the bytecode, nothing is decompiled; collected once per session, with `progress` for a ticket |
| `readMappings` | `session`, `path` | `{format, renames, comments, matched, mappings}`: a mapping file (anything mapping-io reads: ProGuard, Tiny, Enigma, SRG, ...) matched to this file's ids. The side whose class names match the file is used, so R8's `mapping.txt` and Fabric's Tiny both work. Classes get simple names |
| `writeMappings` | `session`, `path`, `format` (`proguard` `tiny2` `enigma` `tsrg2`), `renames`, `comments`? | `{written}`: renames (and comments, where the format has them) as a mapping file; ProGuard with readable names on the left, as R8 writes it |
| `export` | `session`, `dir`, `ticket`? | `dir`, `written`, `failed`, `ms` |
| `cancel` | `ticket` | `cancelled`: whether a job with that ticket was running |
| `overview` | `session` | see below |
| `files` | `session` | `files: [{path, type, size}]`: everything but code |
| `file` | `session`, `path` | `{path, kind, size, text?, data?, mime?, children?, truncated}` |
| `setCodeData` | `session`, `renames`? `{node: name}`, `comments`? `{node: text}` | `applied`. Replaces all earlier ones. |
| `close` | `session` | `{}` |
| `shutdown` | | `{}`, then exit |

**Ids.** Classes are original internal names (`com/foo/Bar`, inner classes
`com/foo/Bar$Inner`), methods `com/foo/Bar.run(I)V`, fields
`com/foo/Bar.count:I`. They never change on rename. Class `kind` is one of
`class` `interface` `enum` `annotation` `record`.

**Nodes** are `{kind, id, top, name, detail, access, static, frida?}`: `kind` is
`class` `method` `field`; `top` is the top-level class whose source declares it;
`name` and `detail` (`run(int): void`) reflect renames; `frida` lists a method's
argument types the way Frida's `overload()` wants them.

**Links.** `decompile` returns `links` (every identifier that names a class,
method or field) and `decls` (the declarations among them) as flat arrays of
four numbers per span: 0-based line, column (UTF-16 units), length, index
into `nodes`.

**Search** scopes are `classes`, `members`, `code`, `strings` (code
matches inside string literals) and `files` (resources and other text files,
resources.arsc's values included); all of them when omitted. Code hits are
`{type: "code"|"string", cls, line, col, len, text}`, one per line; file hits
the same with `type: "file"` and `path` instead of `cls`; name hits are
`{type: "class"|"method"|"field", cls, node}`. Searching code decompiles
every class once, so the first search of a big APK takes a while; it sends
`progress` notifications when given a `ticket`, and `cancel` stops it with a
`CANCELLED` error. `export` works the same way.

**Overview** has `path`, `kind`, `size`, `classes`, `methods`, `fields`;
for archives `files`, `dex`, `nativeLibs: [{abi, name, size}]`,
`javaVersions: [{java, classes}]`, `jarManifest`, and `signing: {schemes,
certs}` (v1 signature files and the v2/v3 signing block, read but not
verified); for APK/AAR the decoded `manifest` and `android` (package,
versions, SDKs, permissions, application flags and `components` with
`exported`, `launcher`, intent actions and deep `links`).

For JAR/AAR also, when present: `plugins` (mod and plugin descriptors:
`fabric.mod.json`, `quilt.mod.json`, `[neoforge.]mods.toml`, `plugin.yml`,
`paper-plugin.yml`, `bungee.yml`, `velocity-plugin.json`, each `{loader,
file, id?, name?, version?, description?, authors, entries: [{kind, cls,
member?}], depends?}`), `mixinConfigs: [{file, package, classes: [{cls,
side}]}]`, `services: [{service, providers}]` from `META-INF/services`,
`artifacts: [{group, artifact, version}]` from `pom.properties`, bundled
`jars: [{path, size}]`, `web` (`web.xml`: `servlets`, `filters` as `{name,
cls, urls}`, `listeners`). For JAR and class input: `mixinTargets` (mixin
class to the classes it targets) and `entryClasses: [{cls, kind, detail}]`
for entry points found by annotation or interface (`neoforge`, `forge`,
`servlet`, `filter`, `listener`, `spring-boot`). Class names in
all of these are Java names with `$` for inner classes.

**Files.** `file` returns `kind` `text` (binary XML decoded; plain text up to
4 MB), `image` (`data` is base64, with `mime`), `binary` (the first 64 KB as
base64 `data`) or `table` for `resources.arsc`, whose `children` are the
`res/values*` files it decodes to; those can then be read with `file` too.

Requests are answered on a thread pool, so responses can arrive out of order.

Error codes: `BAD_REQUEST`, `UNKNOWN_METHOD`, `NO_SESSION`, `NO_CLASS`,
`NO_ENGINE`, `NO_NODE`, `NO_FILE`, `UNSUPPORTED_INPUT`, `OPEN_FAILED`,
`DECOMPILE_FAILED`, `DECODE_FAILED`, `EXPORT_FAILED`, `CANCELLED`, `INTERNAL`. Malformed
requests get an error with `"id": null`.

Bump `Version.PROTOCOL` (and `PROTOCOL` in `src-tauri/src/engine/process.rs`)
on any incompatible change.

## Running it by hand

```sh
printf '%s\n' '{"id":1,"method":"open","params":{"path":"/abs/app.apk"}}' \
  | src-tauri/engine-dist/runtime/bin/java -jar src-tauri/engine-dist/engine.jar
```

The app accepts overrides for debugging:

| Variable | Effect |
|---|---|
| `JREVERSE_JAVA` | `java` binary to use instead of the bundled one |
| `JREVERSE_ENGINE_JAR` | engine jar to use instead of the bundled one |
| `JREVERSE_ENGINE_JVM_ARGS` | extra JVM flags, e.g. `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005` (needs a full JDK via `JREVERSE_JAVA`) |
