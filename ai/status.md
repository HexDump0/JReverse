# Status

_Last verified: 2026-10-11_

## Works

- Home rows show what the file calls itself (2026-10-11): app label, package and version, and the
  launcher icon (PNG/WebP, or an adaptive icon with a bitmap foreground); mods and plugins by their
  descriptor name and icon. Read after open by the engine's `identity`, kept in recents (`icons/` in
  the app data dir). The engine warms up (`warm`) while home is up: ~0.4 s off a 2.4 s first open.
  The palette lists the file's own classes before library classes that match as well.
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
  - JVM jars (2026-10-07): the Overview reads mod/plugin descriptors (Fabric, Quilt, NeoForge/Forge,
    Bukkit/Paper, Bungee, Velocity), mixins and targets, services, bundled jars and Maven artifacts,
    web.xml, and @Mod/Spring Boot/servlet entry classes. Inspector "Copy as": Frida/Xposed/Smali for
    Android, Reference/Descriptor (+ Mixin for mods) for JVM.
  - Strings panel (2026-10-07): every string constant from bytecode, grouped by shape, libraries hidden.
  - Mappings (2026-10-07, File menu): import whatever mapping-io reads (R8 mapping.txt, Tiny, Enigma,
    SRG), export ProGuard/Tiny v2/Enigma/TSRG v2. Classes get simple names only (no package moves).
- Inputs: APK, AAB (code only, see below), AAR, JAR/WAR, DEX, single `.class`. Detection reads
  contents, not the extension.
- Engine crash recovery: pending requests fail, the next request restarts the JVM, reopens the
  file and re-applies renames.
- Tests green as of 2026-10-11: engine 33 (incl. smoke on the jlink runtime), Rust 17,
  frontend 35, `pnpm check` 0 errors. CI (`.github/workflows/ci.yml`, ubuntu only)
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

- 2026-10-06: the whole UI rebuilt to the approved `design/workbench-v5.html`, merged into `master`
  as `a02d645` (2026-10-07): panel islands on a dark frame, panel rail, breadcrumb in the title bar,
  Inspector replacing the Outline, comments as notes in the code, scrollbar marks, class map on the
  Overview, a quiet home screen (no prism stage), three themes. The owner approved v5 ("I like it
  very much"). Checked by screenshot (real engine, headless Chromium) on the example JAR and a
  3,000-class APK; not yet in the real Tauri window.

## Not release-ready (2026-10-05)

The owner calls it "usable-ish, not ready for release". Gaps found on 2026-10-05:
- No `LICENSE` and no third-party `NOTICE` (jadx, Vineflower and the bundled JRE all ship with it).
- Linux only: never run on Windows or macOS, and CI builds only on ubuntu. No `tauri build`
  artifacts, signing or updater.
- Engine JVM has no `-Xmx`, so the JVM picks its default heap size. A 314 MB APK runs out of heap
  on open (2026-10-07).
- Split APKs (XAPK/APKS/APKM) can't be opened.
- No settings (font size, theme, decompiler options). App icons in `src-tauri/icons/` are the brand ones.

## Repo state

`master` = `origin/master` at `a98555e` (2026-10-11). No other branches; `ui-redesign` was
committed into `master` as `a02d645`.
