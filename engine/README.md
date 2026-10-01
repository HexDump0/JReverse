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

## Protocol (version 1)

One compact JSON object per line. stdout carries protocol messages only;
logs go to stderr. The engine exits on `shutdown` or when stdin closes.

```jsonc
← {"method":"ready","params":{"protocol":1,"version":"0.1.0","engines":["jadx"]}}
→ {"id":1,"method":"open","params":{"path":"/x/app.apk"}}
← {"id":1,"result":{"session":"s1","kind":"apk","classCount":4812,"ms":1339}}
→ {"id":2,"method":"decompile","params":{"session":"s1","class":"a/b"}}
← {"id":2,"error":{"code":"NO_CLASS","message":"class not found: a/b"}}
```

| Method | Params | Result |
|---|---|---|
| `open` | `path` | `session`, `kind` (`apk` `aar` `jar` `dex` `class`), `classCount`, `ms` |
| `listClasses` | `session` | `[{id, kind}]`, top-level classes sorted by id |
| `decompile` | `session`, `class`, `engine`? (default `jadx`) | `source`, `engine`, `ms`, `warnings` |
| `close` | `session` | `{}` |
| `shutdown` | | `{}`, then exit |

Class ids are original internal names (`com/foo/Bar`) and never change on
rename. `kind` is one of `class` `interface` `enum` `annotation` `record`.
Requests are answered on a thread pool, so responses can arrive out of order.

Error codes: `BAD_REQUEST`, `UNKNOWN_METHOD`, `NO_SESSION`, `NO_CLASS`,
`NO_ENGINE`, `UNSUPPORTED_INPUT`, `OPEN_FAILED`, `DECOMPILE_FAILED`,
`INTERNAL`. Malformed requests get an error with `"id": null`.

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
