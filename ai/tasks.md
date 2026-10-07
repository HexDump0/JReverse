# Tasks

Claim work here before starting so two agents don't build the same thing.
Format: `- [agent-tag, YYYY-MM-DD] what, which files`. Delete the line when
done and add a `log.md` entry.

## In progress


## Next (owner's plan for the next session)

- The v5 redesign is built on branch `ui-redesign` (uncommitted, 2026-10-06). The owner reviews it in
  the real app, then decides on committing. History of the rounds: v2 to v5 in `design/` and log.md.
- Check the redesign in the real Tauri window: dragging by the title bar (the drag regions moved
  into a three-column grid), the theme script in `app.html`, drop anywhere.
- Real app identity on home rows and the Overview: launcher icon, app label, package + version
  (`WhatsApp`, `com.whatsapp 2.24`). Needs the engine (or `peek.rs`) to read the manifest and icon
  cheaply at peek time; the Overview already parses the manifest. Home now shows a kind icon instead.
- Start the engine JVM while the home screen is up, so the first open is faster.
- Palette: rank the app's own classes above library classes (typing "Rec" lists androidx first).
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
