# Tasks

Claim work here before starting so two agents don't build the same thing.
Format: `- [agent-tag, YYYY-MM-DD] what, which files`. Delete the line when
done and add a `log.md` entry.

## In progress


## Next (owner's plan for the next session)

- Owner to judge the motion pass (View > Motion toggles it) and the attached tabs in the real window.
- Check the redesign in the real Tauri window: dragging by the title bar (the drag regions moved
  into a three-column grid), the theme script in `app.html`, drop anywhere.
- Real app identity on the Overview too (home rows have it since 2026-10-11): the engine's `identity`
  has the label, version and icon; the Overview header still shows the file name.
- Vector launcher icons (adaptive icons with a vector foreground) aren't drawn: convert the vector
  drawable to SVG in the engine. Seen on `BG-Video-Recorder.apk`.
- Open question for the owner: commit `design/` and `brand/`? They're in `.git/info/exclude`, so
  other clones and worktrees don't have the mockups.

## Backlog

Not prioritised by the owner yet. Ask before starting anything big.

- Gaps found comparing with jadx-gui, Recaf 4, Bytecode Viewer, JEB (2026-10-07; the owner ruled out patching on 2026-10-07):
  split APK bundles (XAPK/APKS/APKM), version diff of two files, class hierarchy / call graph,
  patching (edit smali or bytecode, rebuild, sign), plugin or scripting API, an MCP server for LLM clients.

- Mappings for classes outside the file (Yarn/Mojang names for `net.minecraft.class_1308` in a mod), and
  imported class names that move packages.
- Engine progress for `open` ("38 of 146"): jadx's `load()` has no progress hook, so this needs
  a look at its internals or a pass of our own.
- AAB: decode the protobuf manifest so the Overview has its Android section
- Vineflower for DEX input (dex2jar step), then CFR / Procyon
- Release basics (after the home screen, owner's order on 2026-10-05): LICENSE + third-party
  NOTICE (jadx, Vineflower, the JRE), `tauri build` on Windows/macOS (CI matrix), signing,
  updater, a heap limit for the engine JVM (a 314 MB APK hits OutOfMemoryError on open, 2026-10-07), a settings page
