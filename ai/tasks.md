# Tasks

Claim work here before starting so two agents don't build the same thing.
Format: `- [agent-tag, YYYY-MM-DD] what, which files`. Delete the line when
done and add a `log.md` entry.

## In progress

_(none)_

## Next (owner's plan for the next session)

- Review the workbench (built 2026-10-03 without `design/mockup.html` in the clone) against the
  mockup and the UI taste notes; adjust on purpose where they disagree.
- Open a PR (or merge) so CI runs for the first time; it only triggers on `master` and PRs.
- Open question for the owner: commit `design/` and `brand/`? They're in `.git/info/exclude`, so
  other clones and worktrees don't have the mockups.

## Backlog

Not prioritised by the owner yet. Ask before starting anything big.

- Engine progress for `open` ("38 of 146"): jadx's `load()` has no progress hook, so this needs
  a look at its internals or a pass of our own.
- AAB: decode the protobuf manifest so the Overview has its Android section
- Vineflower for DEX input (dex2jar step), then CFR / Procyon
- Packaging and releases (`tauri build` per OS, signing)
