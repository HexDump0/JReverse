# Status

_Last verified: 2026-10-01_

## Works

- No OS title bar; the app bar drags the window and has its buttons (2026-10-01).
- First launch shows a one-screen onboarding; after it, the bundled example `vault-example.jar` is a default entry in the start screen's list (2026-10-01).
- Start screen (2026-10-01): recent files kept by Rust (`recent.json` in the app data dir),
  peek readout (first bytes + detected kind, or why it can't open), drag and drop, opening with
  cancel (Esc), remove/clear with Ctrl Z undo, reveal in folder, locate a moved file, Ctrl P palette,
  File/View menus, Log drawer. After a file opens, the old test view (`src/lib/Workbench.svelte`) shows.
- Open → list classes → decompile, end to end, with JADX.
- Inputs: APK, AAR, JAR (WAR is detected as JAR since its classes are plain
  `.class` entries), DEX, single `.class`. Detection reads file contents, not the extension.
- Engine crash recovery: pending requests fail, the next request restarts the
  JVM and reopens the session's file.
- Tests are all green as of 2026-10-01: engine 10 + smoke 1, Rust 8,
  `pnpm check` 0 errors. A manual run against gson-2.14.0.jar opened 86 classes
  in about 0.7 s and decompiled them cleanly.

## Not built yet

Roughly in the order the design implies:

- **Real UI.** The start screen is done; after opening, the page is still a plain test bench. The target is
  `design/mockup.html` + `design/DESIGN.md`: overview/triage page, package
  tree, split panes, Java/Smali/Graph/Hex views, `Ctrl+P` palette, references
  panel, status bar, themes.
- **More decompilers.** Only `jadx` is registered. Vineflower, CFR and Procyon
  are planned (the brand mark depicts four). Implement `Backend`, then register it in `Sessions`.
  Note: those three read only JVM `.class` files, so APK/DEX input needs a
  DEX→class step first (e.g. dex2jar), run once per file. Class ids must match JADX's.
- **Engine features** the UI will need: smali output, control-flow graph,
  cross-references, string search, manifest and resources, renames and comments.
- **Inputs:** AAB is rejected with an explicit "not supported yet" error.
  Loose smali files aren't accepted as input. The root README advertises both.
- Packaging, releases and CI: nothing set up yet.

## Design

- Start screen: implemented in `src/lib/start/` from `design/start-mockup.html` (2026-10-01).
- Main workbench: `design/mockup.html` + `design/DESIGN.md`, not implemented yet.

## Repo state

All code is committed on `master` (commits on 2026-10-01). Nothing has been pushed yet.
