# Log

One entry per session, newest first. Format:

```
## YYYY-MM-DD — agent-tag — short title
What was done. What's left. Anything the next agent must know.
```

---

## 2026-10-03 — claude-q8b — the workbench, engine protocol v2, Vineflower, files
- Engine protocol v2 (`engine/README.md`): code links, smali/bytecode, usages, search (names, code,
  strings, files) with progress and cancel, export, overview (manifest, components, v1/v2/v3
  signing), renames/comments, files and resources, Vineflower for class files, AAB input, jadx
  deobfuscation ("generated names"). 25 engine tests.
- Rust: commands for all of it, progress events, code-data replay after a restart, `projects.rs`.
- UI: the real workbench in `src/lib/workbench/` (see project.md): tree, tabs, code view, outline,
  usages, search, notes, files, overview, Frida/Xposed snippets, F1 shortcuts; the Continue card
  shows where you were. Vitest (26 tests) and a CI workflow added.
- Verified with a real-engine browser harness and in the real app under Xvfb (gotchas, Testing),
  on F-Droid and NewPipe APKs. A branch review found 6 bugs, all fixed (one: null params).
- Not pushed: GitHub returned 403. Built without `design/mockup.html`; review against it.

## 2026-10-01 — claude-s7k — no title bar, first-run welcome, bundled example
- Window is undecorated (`decorations: false`). The app bar is the drag region (`data-tauri-drag-region`)
  and has minimize/maximize/close; window permissions added in `capabilities/default.json`.
- Onboarding (`src/lib/start/Onboarding.svelte`), first launch only: full window, mark with its beam drawn
  in from the window edge, "JReverse", "A modern Java decompilation tool", one "Get started" button (Enter).
  The app bar goes bare (drag + window buttons). Done-flag: localStorage `jreverse.onboarded`.
- Main menu is the normal start screen; the bundled example is always in the list (`known` in
  StartScreen.svelte): Continue card on first run ("Example", Enter to open), last row after that.
  `VITE_FIRST_RUN=1` (owner's `.env`) shows onboarding every launch and hides recents.
- `src/lib/java/highlight.ts` (Java highlighter) is unused for now; kept for the workbench's Java view.
- Bundled example: `engine/src/example/` (a password vault with an XOR-hidden key prefix, built with
  `-g:none`) -> `exampleJar` -> `src-tauri/engine-dist/vault-example.jar`; `example_file` command finds it.
  Valid key for owner "alice": `VLT-9921-53AC` (4 digits summing to 21, tag from `LicenseCheck.tag`).
- Rejected on the way: landing page, `Welcome.java` code view, a plain column, a live example preview,
  feature lists, a two-step intro, a logo footer, suggesting jars found on disk. See conventions.md.
- Not checked in the real window: dragging, double-click maximize and edge resizing on Linux.

## 2026-10-01 — claude-s7k — start screen implemented
- Built the start screen from `design/start-mockup.html` in `src/lib/start/`, plus shared chrome in
  `src/lib/shell/` (AppBar with File/View menus, StatusBar, Palette) and Rust `peek.rs` + `recents.rs`.
- Fonts are bundled via `@fontsource` (no Google Fonts at runtime). Start-screen tokens are in `app.css`.
- Verified with headless Chromium + mocked IPC (see gotchas) for home, first run, opening, bad file,
  palette and the workbench. Not clicked through in the real Tauri window. Rust 12 tests, `pnpm check` clean.
- Deliberate gaps: indeterminate progress (no engine progress), no package ident / "last at" on the
  Continue card, first-run lists only working shortcuts. Nothing committed.

## 2026-10-01 — claude — start screen mockup (approved)
- `design/start-mockup.html` is the approved start screen after many review rounds. The states
  are deep-linked with `#home`, `#first`, `#drag`, `#opening` and `#bad`.
  Published copy: https://claude.ai/artifact/BUarFZeH81fdqbo4jAws2Y
- Read the "UI taste" section of conventions.md before any UI work. The owner rejected these,
  each for a stated reason: generic slop, flashy effects, a plain JetBrains clone, animation,
  heavy gradients, Unicode icons, makeup bars.
- Next session (owner's plan): implement the start screen in `src/`. See tasks.md for what it needs.

## 2026-10-01 — claude — repo survey, tests, first commits, ai/ notes
- Surveyed the codebase. The engine, Rust client and test page all work end to
  end with JADX. All tests pass (see status.md).
- Committed the previously uncommitted work as 5 commits on `master` (not pushed).
- Created `ai/` and a root `AGENTS.md` / `CLAUDE.md` pointing here.
- Next: the owner hasn't picked a priority. See the tasks.md backlog.
