# Tasks

Claim work here before starting so two agents don't build the same thing.
Format: `- [agent-tag, YYYY-MM-DD] what, which files`. Delete the line when
done and add a `log.md` entry.

## In progress

_(none)_

## Next (owner's plan for the next session)

- **Implement the start screen** from `design/start-mockup.html` in `src/`. It replaces the test
  page's empty state. After a file opens, keep the old test view until the real shell exists.
  What it needs beyond the mockup's HTML/CSS:
  - Recent files kept by the Rust side, e.g. a JSON file in the app data dir with path, kind,
    class count, size and last-opened time. Package name/version and "last location / renames /
    comments" don't exist yet, so render them only when present.
  - A cheap "peek" for the header readout: a Rust command that returns the first 16 bytes and the
    detected kind without starting a full open (or reuse the engine's InputDetector rules).
    Friendlier "this is a PDF / ELF" errors would need the same peek.
  - Drag and drop already works via `getCurrentWebview().onDragDropEvent` (see the test page).
  - Opening progress ("Loading classes 38 of 146") needs engine progress notifications. Protocol v1
    has none, so start with an indeterminate spinner or add a notification (bump PROTOCOL in both places).
  - Rules: Tabler icons inlined as SVG, no Unicode symbols, no animation, minimal gradients.
- Open question for the owner: commit `design/` and `brand/`? They're in `.git/info/exclude`, so
  other clones and worktrees don't have the mockups.

## Backlog

Not prioritised by the owner yet. Ask before starting anything big.

- Replace the test page with the real shell from `design/mockup.html`
- Add Vineflower backend (then CFR, Procyon)
- Engine: smali output per class
- Engine: string search and cross-references
- Engine: APK overview data (manifest, permissions, signing, native libs)
- AAB input support
- CI: run `pnpm engine:test`, `cargo test` and `pnpm check`
