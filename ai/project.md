# Project

**JReverse** is a desktop reverse-engineering workbench for Java and Android.
Open an APK/JAR/AAR/DEX/class file, browse classes, read decompiled source.
The goal is a keyboard-first workbench modelled on Binary Ninja and
jadx-gui. Supporting several decompilers (Vineflower, JADX, CFR, Procyon) and
switching between or comparing them is **one feature among many, not the core
of the product** (owner, 2026-10-01). Don't let it drive priorities.

Solo-owner project (GitHub user HexDump0), package `io.github.hexdump0.jreverse`.

## Architecture

```
Svelte 5 UI ──invoke()──▶ Rust / Tauri 2 ──JSON lines on stdin/stdout──▶ JVM engine
src/                       src-tauri/src/                                engine/
```

- **Engine** (`engine/`, Java 21, Gradle): a separate JVM process that runs
  the decompilers. Protocol v1 is documented in `engine/README.md`; that file
  is the source of truth for methods and error codes.
- **Rust client** (`src-tauri/src/engine/`): starts the engine on first use,
  routes responses by id, and restarts it after a crash. Sessions survive a
  restart: the frontend holds `session-N` ids that map to the engine's own
  `sN` ids, and a file is reopened transparently after a restart.
- **Tauri commands** (`src-tauri/src/commands.rs`): `open_file` (also records the
  file in recents), `list_classes`, `decompile_class`, `close_session`, `peek_file`
  (`peek.rs`, mirrors `InputDetector`), `recent_files`, `forget_recent`, `replace_recents` (`recents.rs`), `example_file`. Events:
  `engine://status`, `engine://log`.
- **Frontend** (`src/`): `src/lib/engine.ts` holds typed wrappers for the commands.
  `src/routes/+page.svelte` owns app state and the open flow; `src/lib/shell/` has the
  app bar, status bar and palette; `src/lib/start/StartScreen.svelte` is the start screen;
  `src/lib/Workbench.svelte` is the temporary test view shown after a file opens.
  `src/lib/java/highlight.ts` colours Java source (used by the welcome preview so far).
- **Bundled runtime**: `pnpm engine` builds a fat jar and a jlink-trimmed
  JRE into `src-tauri/engine-dist/` (gitignored). Tauri bundles it as the
  `engine/` resource. Users never need Java.

## Where things live

| Path | What |
|---|---|
| `engine/src/main/java/.../engine/rpc/` | Protocol: `Server` (thread pool), `Methods`, `ErrorCode` |
| `engine/src/main/java/.../engine/session/` | `InputDetector` (by magic bytes), `Session(s)` |
| `engine/src/main/java/.../engine/backend/` | `Backend` interface + `JadxBackend`. Add new decompilers here. |
| `engine/src/fixtures/` | Tiny Java classes compiled into `fixture.jar` for tests |
| `engine/src/example/` | The bundled example app, built into `engine-dist/vault-example.jar` by `pnpm engine` |
| `engine/build.gradle.kts` | Shadow jar, jlink runtime (`extraModules` / `excludedModules`), `smokeTest` |
| `src-tauri/src/engine/` | `mod.rs` (Engine, sessions), `process.rs` (one JVM, routing), `locate.rs` (finding java and the jar), `tests.rs` |
| `design/DESIGN.md`, `design/mockup.html` | The target UI, spec and full HTML mockup. **Local only**, see gotchas. |
| `brand/BRAND.md` | Logo, colours, type. **Local only.** |

## Stack versions

Tauri 2, Svelte 5 (runes), SvelteKit with adapter-static, Vite 8,
TypeScript 6, pnpm 11. Rust edition 2021 with tokio. JDK 21, Gradle 9, jadx
1.5.6, Gson, JUnit 6.
