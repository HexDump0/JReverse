# Tasks

Claim work here before starting so two agents don't build the same thing.
Format: `- [agent-tag, YYYY-MM-DD] what, which files`. Delete the line when
done and add a `log.md` entry.

## In progress

_(none)_

## Next (owner's plan for the next session)

- Start screen leftovers (2026-10-01): engine progress notifications for "38 of 146" (protocol has
  none, the UI shows an indeterminate spinner); package name/version and "last at" for the Continue
  card; the first-run shortcut list only shows Ctrl O / Ctrl P until N, X and Tab exist.
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
