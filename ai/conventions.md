# Conventions

## Commits

The owner wants commits to look human-written:

- One short lowercase line, **no body**. Use the prefixes `feat:`, `imp:`
  (implement), `fix:`, `chore:`, `docs:`, `refactor:`, `test:`.
- Examples: `feat: add jvm engine with jadx backend`, `imp: engine client and tauri commands`.
- Split work into logical commits instead of one big dump.
- Only commit or push when the owner asks.

## Code style

- Java: tabs, `final` classes, small records (`ClassEntry`, `Decompiled`),
  errors as `RpcException(ErrorCode, message)`.
- Rust: 4 spaces, `thiserror` errors, async with tokio. Every error that
  reaches the frontend is `{code, message}`.
- Svelte 5 runes (`$state`, `$derived`, `$props`). Theme colours are CSS custom
  properties in `src/app.css`, with values taken from `design/DESIGN.md`.
- Comments are sparse and explain *why*. Match the surrounding code.

## Commands

```sh
pnpm install
pnpm tauri dev                 # builds the engine, then runs the app
pnpm engine                    # build engine.jar + runtime into src-tauri/engine-dist/
pnpm engine:test               # engine tests + smoke test (see gotchas: caching)
cd src-tauri && cargo test     # Rust client tests
pnpm check                     # svelte-check / TypeScript
```

Debug overrides: `JREVERSE_JAVA`, `JREVERSE_ENGINE_JAR`,
`JREVERSE_ENGINE_JVM_ARGS` (see `engine/README.md`).
