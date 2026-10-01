# Decisions

Why things are the way they are. Newest first. Add an entry when you make a
choice someone might later question.

### Decompilers run in a separate JVM, not embedded (by 2026-10-01)
They're Java libraries, and the app is Rust/Tauri. A child process talking
JSON lines keeps a crash or OOM in a decompiler from taking down the app; the
client simply restarts it. It also allows several decompilers in one process later.

### Ship a jlink runtime instead of requiring Java (by 2026-10-01)
Users shouldn't need a JDK. The runtime is trimmed by `jdeps`, minus
`java.desktop` and `java.sql`, which saves about 20 MB.

### Input type comes from file contents, not the extension (by 2026-10-01)
Renamed or extensionless files are common in RE work. See `InputDetector`.

### Class ids are original internal names (by 2026-10-01)
`com/foo/Bar` never changes, even after renames are implemented, so the UI
can key everything on it.

### UI follows `design/DESIGN.md` (by 2026-10-01)
Code-first, one accent colour for "where you are", keyboard shortcuts
compatible with jadx. When the app and the doc disagree, change one of
them on purpose.
