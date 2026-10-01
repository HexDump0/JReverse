# JReverse

A reverse-engineering workbench for Java and Android APK, AAB, AAR, JAR, WAR,
DEX and smali.

## Development

Needs Node with pnpm, Rust, and JDK 21 (only to build the engine; the app
ships its own Java runtime).

```sh
pnpm install
pnpm tauri dev      # builds the engine, then runs the app
pnpm engine:test    # engine tests (cd src-tauri && cargo test for the client)
```

The decompilers run in a separate JVM process; see [engine/README.md](engine/README.md).
