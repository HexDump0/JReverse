# Log

One entry per session, newest first. Format:

```
## YYYY-MM-DD — agent-tag — short title
What was done. What's left. Anything the next agent must know.
```

---

## 2026-10-01 — claude — start screen mockup (approved)
- `design/start-mockup.html` is the approved start screen after many review rounds. The states
  are deep-linked with `#home`, `#first`, `#drag`, `#opening` and `#bad`.
  Published copy: https://claude.ai/artifact/BUarFZeH81fdqbo4jAws2Y
- Read the "UI taste" section of conventions.md before any UI work. The owner rejected these,
  each for a stated reason: generic slop, flashy effects, a plain JetBrains clone, animation,
  heavy gradients, Unicode icons, makeup bars.
- Next session (owner's plan): implement the start screen in `src/`. See tasks.md for what it needs.

## 2026-10-01 — claude — repo survey, tests, first commits, ai/ notes
- Surveyed the codebase. The engine, Rust client and test page all work end to
  end with JADX. All tests pass (see status.md).
- Committed the previously uncommitted work as 5 commits on `master` (not pushed).
- Created `ai/` and a root `AGENTS.md` / `CLAUDE.md` pointing here.
- Next: the owner hasn't picked a priority. See the tasks.md backlog.
