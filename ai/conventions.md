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

## UI taste (owner feedback, 2026-10-01)

The owner rejected the first start-screen mockup as "AI slop":
- Everything sat at the same low contrast, so nothing stood out.
- The selected row was marked with a coloured stripe down its left edge.
- It was a generic table of rows with all-caps section labels and boxed chips.

The second try was also rejected as too flashy. That one had a beam-and-prism effect, a
"spectrum" bar the owner couldn't read, and filler copy like "Nothing is uploaded".
A third try, a plain table plus details panel in the JetBrains/Cutter style, was rejected as boring.
The owner preferred the second one, toned down. So the target sits between the two: keep the
character (dark stage with bold "Drop a file to decompile." type, prism on the seam with a static
beam, a faint ray to the selected file, a "Continue" card focused on resuming), with no filler copy.
The signature table and all class-makeup bars were removed as not useful on this screen; the
makeup breakdown belongs on the Overview. Every visual element has to be labelled or self-explanatory.
Never use left-edge stripes.

Icons: use Tabler icons (tabler.io/icons, outline set) as inline SVG. No Unicode symbols as
icons or separators (no arrows, middle dots, ellipses or curly quotes in UI text).
Gradients: keep them rare. The beam and the selection ray fade out because that is the light
effect itself. Use flat colours for everything else (no radial glows on selected items).
Motion: none. The owner rejected a subtle GSAP pass (intro, light pulses, gliding ray) and wants no
animation. Only functional progress indicators (spinner, loading progress) move.
`design/start-mockup.html` is the approved direction for the start screen (2026-10-01).

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
