# Status

_Last verified: 2026-10-05_

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
  frontend 25, `pnpm check` 0 errors, clippy clean. CI (`.github/workflows/ci.yml`, ubuntu only)
  ran green on `master` for the first time on 2026-10-03.
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

- 2026-10-06: the whole UI rebuilt to the approved `design/workbench-v5.html` (branch `ui-redesign`):
  panel islands on a dark frame, panel rail, breadcrumb in the title bar, Inspector replacing the
  Outline, comments as notes in the code, scrollbar marks, class map on the Overview, a quiet home
  screen (no prism stage), three themes. Checked by screenshot (real engine, headless Chromium) on the
  example JAR and a 3,000-class APK; not yet in the real Tauri window.

## Not release-ready (2026-10-05)

The owner calls it "usable-ish, not ready for release". Gaps found on 2026-10-05:
- No `LICENSE` and no third-party `NOTICE` (jadx, Vineflower and the bundled JRE all ship with it).
- Linux only: never run on Windows or macOS, and CI builds only on ubuntu. No `tauri build`
  artifacts, signing or updater.
- Engine JVM has no `-Xmx`, so the JVM picks its default heap size. Big APKs (40k+ classes) are untested.
- No settings (font size, theme, decompiler options). App icons in `src-tauri/icons/` are the brand ones.

## Repo state

`master` = `origin/master` at `d024152`. The 2026-10-03 branch `claude/sharp-mendel-qbctaf` was
fast-forwarded into it.
