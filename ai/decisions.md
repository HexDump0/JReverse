# Decisions

Why things are the way they are. Newest first. Add an entry when you make a
choice someone might later question.

### What JReverse is: a tool for understanding compiled Java (owner, 2026-10-07)
For everyone who reads JVM or Android code (mods, plugins, server apps, libraries, APKs), not
an Android tool with JAR support. Reading only: no patching or bytecode editing, no debugger.
The screens adapt to what was opened (a Fabric mod, a WAR, a plain JAR, an APK). The owner
rejected a "is this safe / what does it do" headline as slop: show facts people use (descriptors,
entry points, strings, bundled libraries), not verdicts or detection scores.

### Links come from jadx's code metadata, sent as flat spans (2026-10-03)
`decompile` returns every identifier that names a class/method/field as `[line, col, len, node]`
quadruples plus a node table, so the UI can link, highlight occurrences and index declarations
without parsing Java. Smali links are derived in the UI, since `Lcom/foo/Bar;->run(I)V` spells the
engine's member ids directly.

### Vineflower is a separate view, class files only (2026-10-03)
It reads JVM bytecode, so DEX would need a dex2jar step first; not worth it before people ask.
It loads on first use, so opening stays fast. It has no link metadata, so its view finds
declarations by reading the text (`findDecl`), and renames don't apply to it.

### Notes are saved per input file, keyed by path (2026-10-03)
`projects/<fnv1a(path)>.json` in the app data dir, opaque to Rust; the frontend owns the shape
(`Project` in engine.ts). Keyed by path rather than content hash so it's instant and a rebuilt APK
at the same path keeps its notes (renames of vanished nodes are skipped). FNV-1a because
`DefaultHasher` isn't stable across Rust versions.

### resources.arsc decodes on request (2026-10-03)
Listing files is instant; decoding the table can take a second or more in big apps, so it only
happens when the user opens it, and its `res/values*` files then join the Files list.

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
