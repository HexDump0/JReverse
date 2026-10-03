# JReverse

A desktop workbench for reverse engineering Java and Android apps. Open an
APK, AAB, AAR, JAR, WAR, DEX or class file and read it as Java.

## What it does

- **Decompiles** with jadx, and for JVM class files also with Vineflower, so
  you can compare the two. Smali for DEX, JVM bytecode for class files.
- **Code you can follow.** Every class, method and field name is a link
  (Ctrl+click or `D`), with back and forward (`Alt+Left`, `Esc`), find usages
  (`X`), an outline of the class, and find in class (`Ctrl+F`).
- **Search** class and member names, decompiled code, string literals and
  resource files, with regex and case options (`Ctrl+Shift+F`).
- **Overview** of the file: package, versions and SDKs; permissions with the
  dangerous and special ones marked; exported components and deep links;
  signing certificates (v1, v2, v3); native libraries; and how much of the
  code is the app's own versus known libraries. It points out debuggable
  builds, debug certificates, cleartext traffic, allowed backups and
  implicitly exported components, and for obfuscated code offers jadx's
  generated names (`C0123a` instead of a dozen classes called `a`).
- **Files**: resources (binary XML and `resources.arsc` decoded), assets,
  configs and images, with a hex view for everything else.
- **Notes**: rename classes, methods and fields (`N`) and comment on them
  (`;`), bookmark lines (`Ctrl+B`). They're saved per file, with your open
  tabs, so the start screen can put you back where you left off.
- **Copy** a Frida (`F`) or Xposed (`Y`) hook for a method, a smali
  reference, or a whole class; save a class, or export every class's source
  to a folder.

`F1` lists every shortcut. They follow jadx-gui where it has one.

## Development

Needs Node with pnpm, Rust, and JDK 21 (only to build the engine; the app
ships its own Java runtime). On Linux, Tauri also needs the WebKitGTK
development packages.

```sh
pnpm install
pnpm tauri dev      # builds the engine, then runs the app
```

| Check | Command |
|---|---|
| Engine tests, including the shipped runtime | `pnpm engine:test` |
| Rust client tests | `cd src-tauri && cargo test` |
| Frontend types and tests | `pnpm check`, `pnpm test` |

CI runs all of them. The decompilers run in a separate JVM process; see
[engine/README.md](engine/README.md) for its protocol.
