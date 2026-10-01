# ai/ — shared notes for agents

Working memory for the AI agents on this project. Code and git history say
*what* exists; these notes say what isn't obvious from them: status, intent,
traps and who is doing what. Several agents work here at once, so keep notes
short, current and easy to skim.

## Read first (in this order)

1. [project.md](project.md): what JReverse is, how the pieces fit, where things live
2. [status.md](status.md): what works today and what's designed but not built
3. [tasks.md](tasks.md): who is working on what, plus the backlog. **Check it before starting.**
4. [gotchas.md](gotchas.md): traps that have already cost someone time

Then as needed:

- [conventions.md](conventions.md): code style, commits, how to test
- [decisions.md](decisions.md): why things are the way they are
- [log.md](log.md): what each session did, newest first

## Rules

- **Claim before you start.** Add your task under *In progress* in `tasks.md`
  with the date and a short agent tag (e.g. `claude-a3f`). Remove it when done.
- **Leave a log entry** at the top of `log.md` when you finish a session:
  what you did, what's left, anything the next agent must know. 3–6 lines.
- **Fix notes that are wrong.** When code and a note disagree, check which is
  right and update the note. A stale note is worse than none.
- **Don't duplicate.** Point to `engine/README.md`, `design/DESIGN.md` and
  so on instead of copying them. Write here only what isn't written anywhere else.
- **Absolute dates** (`2026-10-01`), never "yesterday".
- Edit only the part of a file you mean to change. Other agents may have
  edited the rest since you read it.
- New file? Add it to the list above.
