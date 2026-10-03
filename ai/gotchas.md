# Gotchas

Things that look wrong, break silently or have already wasted time. Add to this.

## Repo

- **Pushing to GitHub failed with 403** on 2026-10-03: the Claude GitHub App has no access to the
  owner's account/org. Commits stay local until that's fixed in claude.ai settings.
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
- **Rust sends `null` for an unset `Option`.** `json!({"limit": limit})` with `None` gives
  `"limit": null`, so every optional engine param must treat null like a missing key (`optInt`,
  `optString` in `Methods`). This once broke every search in the real app while the tests passed.
- Changing the protocol means bumping **both** `Version.PROTOCOL` (Java) and
  `PROTOCOL` in `src-tauri/src/engine/process.rs`.
- The shadow jar must merge `META-INF/services` (`mergeServiceFiles()` +
  `DuplicatesStrategy.INCLUDE`). Otherwise jadx's DEX input plugin silently
  disappears and APKs open with 0 classes.
- jlink runtime: a `NoClassDefFoundError` in the smoke test means a module is
  missing. Adjust `extraModules` / `excludedModules` in `engine/build.gradle.kts`.
- Engine JVM runs with `-Xss8m`, because jadx recurses deeply on big methods.
- **`JavaClass.getFields()`, `getMethods()` and `getInnerClasses()` decompile the class** (they go
  through `JavaClass.load()`), and after decompiling they hide enum constants and synthetic members.
  Walk `ClassNode` (`getFields()`, `getMethods()`, `searchFieldByShortId`) and convert with
  `jadx.getJavaNodeByRef(...)` instead. `getClassesWithInners()` is safe.
- jadx renames: clearing a rename from the code data isn't enough; call `removeAlias()` on the node.
  After changing code data, drop decompiled code with `ClassNode.unloadFromCache()` + `deepUnload()`
  (what `reloadCode()` does), or comments won't show.
- Vineflower decompiles one class at a time here (the class + its `$` inner classes as the source,
  the whole archive as a library). A fresh `Fernflower` per call; it isn't reentrant.

## Frontend

- Svelte trims whitespace at the start of an element's content: `<span class="dim"> build 12</span>`
  renders with no space. Space it with CSS (`margin-left`) or `{" "}` outside the span.
- The `font:` shorthand in a component's style resets `font-variant-ligatures`, so JetBrains Mono
  draws `==` as one glyph. `app.css` sets it with `!important` on `*` for that reason.
- Flex children that hold the code view need `min-width: 0`. Without it a long line makes the column
  as wide as the line, and focusing the code scrolls the whole window sideways.
- Ctrl+A would select the whole window: chrome is `user-select: none`; mark selectable areas with
  the `selectable` class. The code view's Ctrl+A selects only the lines on screen (virtualized).

## Testing

- Better than mocked IPC: a small Node bridge that runs the real engine jar and answers the app's
  commands over HTTP, with Playwright (`playwright-core`, Chromium at
  `/opt/pw-browsers/chromium-1194/chrome-linux/chrome`) injecting a `__TAURI_INTERNALS__` whose
  `invoke` posts to it. Real decompiler output, real search progress. Not in the repo (2026-10-03,
  claude-q8b kept it in its scratchpad); rebuild it from this description if you need it. Make it
  behave like the Rust commands (nulls for unset options, pass results through whole), or it hides
  bugs that the real app has.
- The real app runs under Xvfb: install `libwebkit2gtk-4.1-dev libgtk-3-dev xvfb xdotool imagemagick`,
  `cargo build`, start `Xvfb :99`, run `target/debug/jreverse` with `DISPLAY=:99` and a throwaway
  `XDG_DATA_HOME`, drive it with `xdotool key`, screenshot with `import -window root`.
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
