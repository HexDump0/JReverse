# Status

_Last verified: 2026-10-01_

## Works

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

- **Real UI.** The current page is a plain test bench. The target is
  `design/mockup.html` + `design/DESIGN.md`: overview/triage page, package
  tree, split panes, Java/Smali/Graph/Hex views, `Ctrl+P` palette, references
  panel, status bar, themes.
- **More decompilers.** Only `jadx` is registered. Vineflower, CFR and Procyon
  are planned (the brand mark depicts four). Implement `Backend`, then register it in `Sessions`.
- **Engine features** the UI will need: smali output, control-flow graph,
  cross-references, string search, manifest and resources, renames and comments.
- **Inputs:** AAB is rejected with an explicit "not supported yet" error.
  Loose smali files aren't accepted as input. The root README advertises both.
- Packaging, releases and CI: nothing set up yet.

## Repo state

All code is committed on `master` (commits on 2026-10-01). Nothing has been pushed yet.
