# Project

**JReverse** is a desktop reverse-engineering workbench for Java and Android.
Open an APK/AAB/AAR/JAR/DEX/class file, browse classes, read decompiled source.
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
  the decompilers. Protocol v2 is documented in `engine/README.md`; that file
  is the source of truth for methods, results and error codes.
- **Rust client** (`src-tauri/src/engine/`): starts the engine on first use,
  routes responses by id, forwards `progress` notifications, and restarts it
  after a crash. Sessions survive a restart: the frontend holds `session-N` ids
  that map to the engine's own `sN` ids, the file is reopened transparently,
  and the last renames/comments (`setCodeData`) are sent again.
- **Tauri commands** (`src-tauri/src/commands.rs`): one per engine method
  (`open_file` also records the file in recents), plus `peek_file` (`peek.rs`,
  mirrors `InputDetector`), recents (`recents.rs`), per-file projects
  (`projects.rs`: renames, comments, bookmarks, open tabs), `write_text_file`
  and `example_file`. Events: `engine://status`, `engine://log`, `engine://progress`.
- **Frontend** (`src/`): `src/lib/engine.ts` holds typed wrappers for every
  command. `src/routes/+page.svelte` owns app state, menus, palette and the open
  flow; `src/lib/shell/` has the app bar, status bar, palette, context menu,
  prompt and shortcut sheet; `src/lib/start/` the start screen and onboarding;
  `src/lib/workbench/` everything after a file opens (below).
- **Bundled runtime**: `pnpm engine` builds a fat jar and a jlink-trimmed
  JRE into `src-tauri/engine-dist/` (gitignored). Tauri bundles it as the
  `engine/` resource. Users never need Java.

## Where things live

| Path | What |
|---|---|
| `engine/.../engine/rpc/` | Protocol: `Server` (thread pool), `Methods`, `ErrorCode` |
| `engine/.../engine/session/` | `InputDetector`, `Session(s)` (decompilers per file), `Overview` (manifest, zip facts), `Signing` (v1/v2/v3 certs), `Export` |
| `engine/.../engine/backend/` | `JadxBackend` (decompile with links, smali, usages, renames), `VineflowerBackend` (class files only), `Nodes` (wire ids), `Search`, `ArchiveFiles` (resources) |
| `engine/src/fixtures/`, `engine/src/test/fixtures/` | Tiny Java classes and smali compiled into test inputs |
| `engine/src/example/` | The bundled example app, built into `engine-dist/vault-example.jar` by `pnpm engine` |
| `src-tauri/src/engine/` | `mod.rs` (Engine, sessions, code-data replay), `process.rs` (one JVM, routing), `locate.rs`, `tests.rs` |
| `src/lib/workbench/workspace.svelte.ts` | One open file: tabs, document cache, history, usages, notes, saving |
| `src/lib/workbench/doc.ts` | Source text model: tokens, links per line, declaration index; Java, smali/bytecode, XML, plain, hex |
| `src/lib/workbench/CodeView.svelte` | Virtualized code view: caret, links, occurrences, find in class |
| `src/lib/workbench/Workbench.svelte` | The shell: sidebar modes, tabs, editor, outline, usages, keys, menus |
| `src/lib/workbench/*Panel.svelte`, `OverviewPage.svelte`, `ClassTree.svelte` | Search, usages, notes, files, overview, class tree |
| `src/lib/workbench/android.ts`, `frida.ts`, `tree.ts` | Library prefixes and permission levels, Frida snippets, tree building |
| `design/DESIGN.md`, `design/mockup.html` | The target UI, spec and full HTML mockup. **Local only**, see gotchas. |
| `brand/BRAND.md` | Logo, colours, type. **Local only.** |

## Stack versions

Tauri 2, Svelte 5 (runes), SvelteKit with adapter-static, Vite 8,
TypeScript 6, Vitest 5, pnpm 11. Rust edition 2021 with tokio. JDK 21,
Gradle 9, jadx 1.5.6, Vineflower 1.12.0, Gson, JUnit 6.
