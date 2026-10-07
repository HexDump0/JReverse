# Log

One entry per session, newest first. Format:

```
## YYYY-MM-DD — agent-tag — short title
What was done. What's left. Anything the next agent must know.
```

---

## 2026-10-07 — claude-k2m — JVM-aware overview, copy formats, strings panel
- Owner's direction (decisions.md): a tool for understanding compiled Java, for everyone, reading only.
- Overview reads mod/plugin descriptors (Fabric, Quilt, NeoForge/Forge, Bukkit/Paper, Bungee, Velocity),
  mixins and their targets, services, bundled jars and Maven artifacts, web.xml, Burp/servlet/@Mod entry classes.
- Inspector "Copy as": Frida/Xposed/Smali for Android, Reference/Descriptor (+ Mixin for mods) for JVM; collapsed.
- Strings panel: every string constant from bytecode (no decompile, ~0.2 s on 7k classes), grouped by shape,
  library strings hidden, Kotlin null-check names dropped, click opens the literal.
- jadx INFO comments off; obfuscated top-level packages grouped in the tree.
- Mappings (File menu): import anything mapping-io reads (R8 mapping.txt, Tiny, Enigma, SRG); names and
  comments fill in, your own win. Export ProGuard/Tiny v2/Enigma/TSRG v2. Classes get simple names only
  (no package moves yet). Yarn/Mojang names for *external* classes (net.minecraft.class_1308) aren't applied.
- A 314 MB APK runs out of heap on open (no -Xmx yet).

## 2026-10-06 — claude-l7x — v5 redesign built into the app
- Owner approved `design/workbench-v5.html` ("I like it very much, let's finalize it"). Built it on
  branch `ui-redesign` (not committed): `app.css` tokens for three themes + `theme.svelte.ts` (View
  menu), Geist font (`@fontsource/geist`, IBM Plex Sans dropped), AppBar with breadcrumb and back and
  forward, StatusBar with engine state and toggles, panel rail and islands in `Workbench.svelte`,
  `Inspector.svelte` (replaces `Outline.svelte`, deleted), tree with libraries grouped
  (`buildClassTree`), obfuscated marks and members, CodeView notes/band/marks, highlighted snippets,
  restyled Search/Notes/Files/Usages, Overview with class map, new home (`StartScreen.svelte`), drop overlay.
- Highlighter has new token kinds `v` (name), `f` (CONSTANT), `p` (punctuation). `Dots.svelte` deleted.
- `pnpm check` clean, 27 Vitest tests pass, `pnpm build` works. Screenshotted with the real engine.
- Left: run it in the Tauri window; see tasks.md.

## 2026-10-06 — claude-l7x — whole-app layout review
- Owner likes the visual direction but says the layout/feel "doesn't feel like a professional thought-out
  tool". Screenshotted home + workbench (real engine via the bridge in gotchas, Testing) and `design/mockup.html`.
- Diagnosis: no layout system. Zones barely differ (`--side` vs `--pane`, `--line` almost invisible); header
  rows don't line up across columns; every panel has its own header style; five different "active" looks;
  the open file's name appears nowhere in the chrome; empty status bar; native scrollbar arrows; search
  scope chips wrap; home uses a separate palette. Proposed: one shell spec (row grid, 3 surfaces, one Panel
  component, one selection style, 4-size type scale), applied in code with before/after screenshots.
  Waiting on the owner's go-ahead.
- Then researched IDA, Binary Ninja, ImHex, JetBrains new UI, Recaf, JEB/jadx at the owner's request.
  The owner wants the flashy features removed or rethought. Proposed cuts: the home stage (prism, beam,
  ray, hero type), key legends, the Continue card, Overview package bars, the separate home palette.
  Proposed useful replacements: home as a tab inside the workbench, a live context panel (usages of what's
  under the caret), marks on the code scrollbar, a status bar with real information.
- Built `design/workbench-v2.html` from that (see tasks.md for the states). Checked by screenshot in
  headless Chromium. In CDP, navigate to about:blank before reloading the same file URL with a new hash,
  or you get the cached page back.
- Owner: v2 "slightly better" but boring, hard on the eyes, not human. Built v3 (see tasks.md) with three
  switchable themes, so the owner can pick by looking. Waiting on the pick.
- v3: serif notes rejected, colours mushy. v4: crisper and neutral, liked. v5: fixes the fuzzy inspector
  and the uneven tree indent (it was 8px for the first level, then 15px). Also fixed the page scrolling
  sideways in the owner's Firefox (`html { overflow: hidden }`). The owner opens mockups in Firefox.

## 2026-10-06 — claude-v2h — home screen round 2 (interactive)
- Owner still wasn't happy with round 1 and asked for research and something "actually usable".
  Looked at Binary Ninja 4's new tab (arrow keys, Ctrl+number, Shift Enter for options), IDA's
  quick start, Wireshark's welcome (sources with live state), HTTP Toolkit (the ADB option shows only
  when a device is attached), JetBrains Recent Locations (code snippets) and jadx PR 2971 (hash history).
- `design/home-v2.html` (local only): D Launcher (one input; search reaches notes; paste a path),
  E Sources (the approved stage, plus open-from: recent, all bookmarks, a phone over adb), F Preview
  (read-only code at the resume point; the ray lands on the caret line). All: app icons, versions
  grouped under one app, Ctrl 1-9, a drop overlay that recognises a newer version of a known app.
- Keys, typing, Ctrl V and real drag and drop work in the prototype. Waiting on the owner's pick.
- Owner: D/E/F feel "crowded, not pleasant to work with" (too many counters, numbers, legends and an
  expanded code card). Added G Quiet: icon, name, version and time per row; a second line only on the
  selected row; six files then "Show all"; no legend. Also fixed the smali excerpt highlighter.

## 2026-10-05 — claude-h5e — notes refresh, home screen review
- Brought the notes up to date: the branch is merged and CI is green on `master`. Added a
  "not release-ready" list to status.md. The home screen redesign is now first in tasks.md.
- Screenshotted onboarding, first run and home (mocked IPC, see gotchas). They match
  `design/start-mockup.html`. The weak points are in the design itself: the left 40% is a dark
  panel with one heading and a lot of empty space. The prism and ray are small and read like a
  glitch. On first run, three quarters of the window is empty. The Continue card has a gap where
  "Last in" is missing. Recent rows are only text: no app icon, label or package.
- Gave the owner home screen ideas (five after dropping one that already exists), now listed in
  tasks.md. The owner wants `ai/` kept current without being asked.
- Mockups in `design/home-options.html` (local only, like all of `design/`):
  - A "Desk": a centred page, drop anywhere, a Continue panel with the open tabs, and a full
    recent table (package, classes, your work).
  - B "Stage": the approved layout with a narrower stage, plus app icons and tabs on Continue.
  - C "Focus": a recent list on the left, and on the right everything about the selected file
    (tabs, bookmarks, renames, file facts, findings). The prism sits on the seam level with the
    selected row.

  All data shown is something JReverse already has (Overview, project, recents). The status bar
  shows the engine state. Waiting on the owner's pick.

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
- Pushed after the owner reconnected GitHub. Built without `design/mockup.html`; review against it.

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
