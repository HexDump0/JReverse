# Status

_Last verified: 2026-10-03_

## Works

- Start screen and onboarding (2026-10-01). The Continue card now says which class you were last in
  and how many notes the file has, and Enter reopens its tabs (2026-10-03).
- Workbench (2026-10-03), replacing the old test bench:
  - Class tree (virtualized, compacted packages, filter), tabs, back/forward history, Ctrl P palette
    with `@` members and `:` lines, F1 shortcut sheet.
  - Code view: virtualized, highlighted Java/smali/bytecode/XML, every class/method/field a link,
    caret with keyboard movement, occurrence highlight, find in class, outline, warnings marked.
  - jadx, Vineflower (JAR/AAR/class only) and smali/bytecode as views of one tab, keeping the member.
  - Find usages (X), rename (N), comment (;), bookmarks, Frida (F) and Xposed (Y) snippets, smali
    references, save class, export all sources. Notes are saved per file in `projects/` in the app
    data dir, along with open tabs and whether generated names are on.
  - Search names, code, string literals and resource files (regex, case), with progress and cancel.
  - jadx deobfuscation ("generated names") per file, from the Overview or the File menu; reopens.
  - Overview: identity, findings (debuggable, debug cert, cleartext, backups, implicit exports,
    obfuscation), exported components and deep links, permissions by level, app code vs libraries,
    signing (v1/v2/v3, read not verified), native libs.
  - Files: resources and assets; binary XML and resources.arsc decoded; images; hex for binaries.
- Inputs: APK, AAB (code only, see below), AAR, JAR/WAR, DEX, single `.class`. Detection reads
  contents, not the extension.
- Engine crash recovery: pending requests fail, the next request restarts the JVM, reopens the
  file and re-applies renames.
- Tests green as of 2026-10-03: engine 25 (incl. smoke on the jlink runtime), Rust 16,
  frontend 25, `pnpm check` 0 errors, clippy clean. CI workflow in `.github/workflows/ci.yml`
  (not run yet: pushing is blocked, see gotchas).
- Checked against a real APK (F-Droid 1.21, 10,166 classes): opens in ~6 s, overview, files,
  usages; a cold code search decompiles everything in ~20 s, later ones are fast.
- Checked in the real Tauri window under Xvfb (2026-10-03): onboarding, open, decompile, usages,
  saving and resuming. Window dragging and resizing are still unchecked.

## Not built yet

- AAB manifest: it's protobuf, so an AAB's Overview has no Android section.
- Vineflower for DEX input (needs a DEX to class step such as dex2jar). CFR and Procyon.
- A call graph / control-flow graph view; hex view of class files.
- Loose smali files as input (the README no longer claims it).
- Packaging and releases: nothing set up beyond `tauri build`.

## Design

- Start screen: implemented in `src/lib/start/` from `design/start-mockup.html` (2026-10-01).
- Workbench: built without the local `design/mockup.html` (not in this clone), following the
  start screen's look and the UI taste notes in conventions.md. Compare with the mockup when it's
  available and adjust on purpose.

## Repo state

Work from 2026-10-03 is committed on branch `claude/sharp-mendel-qbctaf`, not merged to `master`.
Pushing failed with a GitHub 403 (the Claude GitHub App lacks access for the owner's org).
