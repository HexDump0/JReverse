# Gotchas

Things that look wrong, break silently or have already wasted time. Add to this.

## Repo

- **`design/` and `brand/` are not in git.** They're excluded in
  `.git/info/exclude` (local, not `.gitignore`), so they don't show in
  `git status` and can't be committed. They exist only on the owner's machine.
  Read them, but don't assume another clone has them.
- A new file in `src-tauri/engine-dist/` isn't copied to `target/debug/engine/` until tauri-build's
  script reruns. `touch src-tauri/build.rs` forces it (seen with `vault-example.jar`).
- `src-tauri/engine-dist/` is a build artifact (gitignored). If it's missing,
  the app reports "run `pnpm engine` to build it" on first use.

## Engine / protocol

- **Responses can arrive out of order.** The engine answers requests on a
  thread pool. When testing by hand, piping `open` and `listClasses`
  together gives `NO_SESSION`, because `listClasses` runs before `open` finishes.
  Wait for the `open` reply first.
- stdout is protocol-only. Anything printed to stdout from engine code breaks
  the client. Log to stderr (slf4j-simple is configured for that).
- Changing the protocol means bumping **both** `Version.PROTOCOL` (Java) and
  `PROTOCOL` in `src-tauri/src/engine/process.rs`.
- The shadow jar must merge `META-INF/services` (`mergeServiceFiles()` +
  `DuplicatesStrategy.INCLUDE`). Otherwise jadx's DEX input plugin silently
  disappears and APKs open with 0 classes.
- jlink runtime: a `NoClassDefFoundError` in the smoke test means a module is
  missing. Adjust `extraModules` / `excludedModules` in `engine/build.gradle.kts`.
- Engine JVM runs with `-Xss8m`, because jadx recurses deeply on big methods.

## Testing

- To screenshot the UI without a desktop window: run `pnpm dev --port 1420`, start headless
  `chromium --remote-debugging-port`, inject a fake `window.__TAURI_INTERNALS__` (with `invoke`,
  `transformCallback` and `metadata.currentWindow/currentWebview`) via CDP
  `Page.addScriptToEvaluateOnNewDocument`, then `Page.captureScreenshot`. Node 22+ has a global
  `WebSocket`, so no deps are needed. `homeDir()` goes through `plugin:path|resolve_directory`.

- Gradle caches test results. `pnpm engine:test` can print UP-TO-DATE without
  running anything. Use `cd engine && ./gradlew test smokeTest --rerun-tasks`
  to really run them.
- The Rust test `real_engine_survives_being_killed` uses the real built engine,
  so run `pnpm engine` first on a fresh clone.
