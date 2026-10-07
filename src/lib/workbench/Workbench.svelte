<script lang="ts">
  // The main window once a file is open: a rail of side panels (classes, files,
  // search, notes), tabs of decompiled classes in the middle, the inspector for
  // what's under the caret on the right, and usages underneath. Keyboard
  // shortcuts follow jadx-gui.
  import { tick, untrack } from "svelte";
  import { open as pickPath, save as pickSave } from "@tauri-apps/plugin-dialog";
  import { revealItemInDir } from "@tauri-apps/plugin-opener";
  import { cancelJob, errorMessage, exportSources, isFileHit, isNameHit, onEngineProgress, writeTextFile, type NodeInfo, type SearchHit, type Usage } from "$lib/engine";
  import Icon from "$lib/Icon.svelte";
  import { fmtN, fmtSize } from "$lib/format";
  import { say, setTask } from "$lib/status.svelte";
  import type { Location, Menu, MenuItem } from "$lib/shell/AppBar.svelte";
  import ContextMenu from "$lib/shell/ContextMenu.svelte";
  import Prompt, { type PromptRequest } from "$lib/shell/Prompt.svelte";
  import type { PaletteItem } from "$lib/shell/Palette.svelte";
  import ClassTree, { KIND_CLASS, KIND_LETTER, type Member } from "./ClassTree.svelte";
  import CodeView from "./CodeView.svelte";
  import FilesPanel from "./FilesPanel.svelte";
  import NotesPanel from "./NotesPanel.svelte";
  import Inspector from "./Inspector.svelte";
  import OverviewPage from "./OverviewPage.svelte";
  import SearchPanel from "./SearchPanel.svelte";
  import UsagesPanel from "./UsagesPanel.svelte";
  import { enclosing, linkAt, wordAt, type Link, type Pos, type View } from "./doc";
  import { fridaSnippet, xposedSnippet } from "./hooks";
  import { originalMember, ownerOf } from "./ids";
  import { dotted, simpleName, type Tab, type Workspace } from "./workspace.svelte";

  let {
    ws,
    home,
    onreopen,
  }: { ws: Workspace; home: string | null; /** Opens the file again, with generated names on or off. */ onreopen: (deobfuscate: boolean) => void } =
    $props();

  async function reopen(deobfuscate: boolean) {
    ws.project.deobfuscate = deobfuscate || undefined;
    await ws.save();
    onreopen(deobfuscate);
  }

  type Side = "classes" | "files" | "search" | "notes";
  const PREFS = "jreverse.workbench";
  const IDENT = /^[A-Za-z_$][\w$]*$/;

  interface Prefs {
    side: number;
    inspector: boolean;
    fontSize: number;
    usages: number;
  }
  const prefs: Prefs = $state({ side: 296, inspector: true, fontSize: 13.5, usages: 230 });
  try {
    const { outline: _, ...saved } = JSON.parse(localStorage.getItem(PREFS) ?? "{}");
    Object.assign(prefs, saved);
  } catch {
    // Defaults it is.
  }
  $effect(() => {
    const json = JSON.stringify(prefs);
    try {
      localStorage.setItem(PREFS, json);
    } catch {
      // Not remembered; nothing else depends on it.
    }
  });

  let side = $state<Side>("classes");
  let filter = $state("");
  let code = $state<CodeView>();
  let searchPanel = $state<SearchPanel>();
  let filterInput = $state<HTMLInputElement>();
  let ctx = $state<{ x: number; y: number; items: (MenuItem | "-")[] } | null>(null);
  let tree = $state<ClassTree>();
  let prompt = $state<PromptRequest | null>(null);
  let exporting = $state<{ ticket: string; done: number; total: number } | null>(null);

  const tab = $derived(ws.active);
  const entry = $derived(ws.doc(tab));
  const doc = $derived(tab.kind === "manifest" ? ws.manifestDoc() : entry?.state === "ready" ? entry.doc : null);
  const pkg = $derived(tab.cls ? dotted(tab.cls.slice(0, Math.max(0, tab.cls.lastIndexOf("/")))) : "");
  const isDex = $derived(ws.opened.kind === "apk" || ws.opened.kind === "aab" || ws.opened.kind === "dex");
  /** The views a class tab can switch between, in the order shown. */
  const views = $derived<{ view: View; label: string; tag: string }[]>(
    isDex
      ? [
          { view: "java", label: "Java", tag: "" },
          { view: "smali", label: "Smali", tag: "smali" },
        ]
      : [
          { view: "java", label: "jadx", tag: "" },
          ...(ws.opened.engines?.includes("vineflower") ? [{ view: "vineflower" as const, label: "Vineflower", tag: "vineflower" }] : []),
          { view: "smali", label: "Bytecode", tag: "bytecode" },
        ],
  );

  /** The Java views and the low-level one: Java and Smali for DEX, Java and Bytecode for class files. */
  const javaViews = $derived(views.filter((v) => v.view !== "smali"));
  const lowView = $derived(views.find((v) => v.view === "smali")!);
  const inspecting = $derived(prefs.inspector && tab.kind === "class");

  /** Members of the class in the active tab, for the tree. */
  const members = $derived.by((): Member[] => {
    if (!doc || tab.kind !== "class" || !tab.cls) return [];
    void ws.names;
    const seen = new Set<string>();
    const out: Member[] = [];
    for (const d of doc.declLines) {
      const n = doc.nodes[d.node];
      if (seen.has(n.id) || n.id === tab.cls) continue;
      seen.add(n.id);
      const params = n.kind === "method" ? (/\(.*\)/.exec(n.detail)?.[0] ?? "()") : "";
      const pos = doc.decls.get(n.id)!;
      out.push({ id: n.id, kind: n.kind, name: n.name, params, line: pos.line, col: pos.col });
    }
    return out;
  });
  const memberAt = $derived(doc && tab.kind === "class" ? enclosing(doc, tab.caret.line)?.id : undefined);

  const notes = $derived(new Set(Object.values(ws.project.comments).flatMap((c) => c.split("\n").map((l) => l.trim()))));
  const bookmarksHere = $derived(tab.kind === "class" && tab.view === "java" ? ws.project.bookmarks.filter((b) => b.cls === tab.cls).map((b) => b.line) : []);
  const isRenamedNode = (id: string) => id in ws.project.renames;
  const noteCount = $derived(Object.keys(ws.project.renames).length + Object.keys(ws.project.comments).length + ws.project.bookmarks.length);

  untrack(() => ws.restore());

  /** For the status bar: where the caret is, and how this class came out. */
  export function where(): string[] {
    if (!doc || tab.kind === "overview") return [];
    const parts = [`Ln ${tab.caret.line + 1}, Col ${tab.caret.col + 1}`];
    if (tab.kind === "class" && tab.view !== "smali") {
      if (doc.warnings) parts.push(`${doc.warnings} ${doc.warnings === 1 ? "warning" : "warnings"}`);
      parts.push(`${doc.engine}, ${doc.ms} ms`);
    }
    return parts;
  }

  /** For the title bar: the file, then package, class and the member the caret is in. */
  export function location(): Location {
    const parts: Location["parts"] = [{ label: ws.name, run: () => ws.showOverview() }];
    if (tab.kind === "overview") parts.push({ label: "Overview" });
    else if (tab.kind === "manifest") parts.push({ label: "AndroidManifest.xml" });
    else if (tab.kind === "file") parts.push(...tab.path!.split("/").map((label) => ({ label })));
    else {
      if (pkg) parts.push({ label: pkg.slice(pkg.lastIndexOf(".") + 1), run: () => ((side = "classes"), (filter = ""), tree?.revealCurrent()) });
      parts.push({ label: ws.className(tab.cls!), run: () => jumpToLine(1) });
      const m = doc && enclosing(doc, tab.caret.line);
      if (m && m.kind !== "class") {
        const pos = doc!.decls.get(m.id);
        parts.push({ label: m.kind === "method" ? `${m.name}()` : m.name, run: pos ? () => jump(pos) : undefined });
      }
    }
    return { parts, back: ws.back.length > 0, forward: ws.fwd.length > 0, onback: () => ws.goBack(), onforward: () => ws.goForward() };
  }

  export function toggleInspector() {
    prefs.inspector = !prefs.inspector;
  }

  /** Whether the inspector is showing, or null where it can't (the Overview, resources). */
  export function inspectorState(): boolean | null {
    return tab.kind === "class" ? prefs.inspector : null;
  }

  /* ---------- what the caret is on ---------- */

  function target(): { node: NodeInfo; link?: Link } | null {
    if (!doc) return null;
    const link = linkAt(doc, tab.caret);
    if (link) return { node: doc.nodes[link.node], link };
    return null;
  }

  function needTarget(what: string): NodeInfo | null {
    const t = target();
    if (!t) say(`Put the caret on a class, method or field name to ${what}`);
    return t?.node ?? null;
  }

  /* ---------- actions ---------- */

  function follow(link: Link) {
    if (!doc) return;
    const node = doc.nodes[link.node];
    if (link.decl && node.kind !== "class") {
      // On a declaration already: show who uses it instead, like an IDE.
      ws.findUsages(node);
      return;
    }
    ws.goToNode(node);
  }

  function goToDeclaration() {
    const t = target();
    if (t?.link) follow(t.link);
    else say("Put the caret on a class, method or field name to go to it");
  }

  function usages() {
    const node = needTarget("find its usages");
    if (node) ws.findUsages(node);
  }

  function rename() {
    const node = needTarget("rename it");
    if (!node) return;
    if (node.kind === "method" && /\.<(cl)?init>/.test(node.id)) {
      say("Constructors take the name of their class; rename the class instead");
      return;
    }
    const original = node.kind === "class" ? simpleName(node.id) : originalMember(node.id);
    prompt = {
      title: `Rename ${node.kind} ${node.name}${node.name !== original ? ` (originally ${original})` : ""}`,
      value: ws.project.renames[node.id] ?? node.name,
      placeholder: original,
      verb: "rename",
      hint: "Leave it empty to restore the original name",
      validate: (v) => (v && !IDENT.test(v) ? "Not a valid Java name" : null),
      onsubmit: (v) => ws.rename(node, v && v !== original ? v : null),
    };
  }

  function comment() {
    const t = target();
    const node = t?.node ?? (doc ? enclosing(doc, tab.caret.line) : undefined);
    if (!node || tab.kind !== "class") {
      say("Put the caret in a class, method or field to comment on it");
      return;
    }
    prompt = {
      title: `Comment on ${node.kind} ${node.name}`,
      value: ws.project.comments[node.id] ?? "",
      placeholder: "What it does",
      verb: "save",
      hint: "Leave it empty to remove the comment",
      multiline: true,
      onsubmit: (v) => ws.comment(node, v || null),
    };
  }

  async function copy(text: string, what: string) {
    try {
      await navigator.clipboard.writeText(text);
      say(`Copied ${what}`);
    } catch {
      say("Couldn't copy to the clipboard", true);
    }
  }

  function frida() {
    if (!doc || tab.kind !== "class") return;
    const node = target()?.node ?? enclosing(doc, tab.caret.line);
    if (!node) return;
    const decl = doc.decls.get(node.id);
    copy(fridaSnippet(node, decl ? doc.lines[decl.line] : undefined), `a Frida snippet for ${node.name}`);
  }

  function copySource() {
    if (doc) copy(doc.lines.join("\n"), tab.kind === "file" ? fileName(tab.path!) : `${ws.className(tab.cls ?? "")} (${fmtN(doc.lines.length)} lines)`);
  }

  function xposed() {
    if (!doc || tab.kind !== "class") return;
    const node = target()?.node ?? enclosing(doc, tab.caret.line);
    if (node) copy(xposedSnippet(node), `an Xposed snippet for ${node.name}`);
  }

  /** `Lcom/foo/Bar;->run(I)V`, the way smali and most hooking tools spell it. */
  function smaliRef(node: NodeInfo): string {
    if (node.kind === "class") return `L${node.id};`;
    const owner = ownerOf(node.id);
    return `L${owner};->${node.id.slice(owner.length + 1)}`;
  }

  function bookmark() {
    if (!doc || tab.kind !== "class" || !tab.cls) return;
    ws.toggleBookmark(tab.cls, tab.caret.line, doc.lines[tab.caret.line]?.trim().slice(0, 120) ?? "");
  }

  function goToLine() {
    if (!doc) return;
    prompt = {
      title: `Go to line (1 to ${fmtN(doc.lines.length)})`,
      value: String(tab.caret.line + 1),
      placeholder: "Line number",
      verb: "go",
      hint: "",
      validate: (v) => (/^\d+$/.test(v) && Number(v) >= 1 ? null : "Type a line number"),
      onsubmit: (v) => jumpToLine(Number(v)),
    };
  }

  export function jumpToLine(n: number) {
    if (!doc) return;
    const line = Math.max(0, Math.min(doc.lines.length - 1, n - 1));
    jump({ line, col: doc.lines[line].search(/\S|$/) });
  }

  function jump(pos: Pos) {
    tab.caret = pos;
    tab.reveal = { line: pos.line, col: pos.col, n: Date.now() };
    tick().then(() => code?.focusCode());
  }

  /** Opens the search panel, filled with the selection or the word under the caret. */
  export async function openSearch(prefill = true) {
    let text: string | undefined;
    if (prefill) {
      const sel = window.getSelection()?.toString().trim();
      if (sel && !sel.includes("\n")) text = sel;
      else if (doc && tab.kind !== "overview") {
        const w = wordAt(doc.lines[tab.caret.line] ?? "", tab.caret.col);
        if (w) text = doc.lines[tab.caret.line].slice(w.start, w.end);
      }
    }
    side = "search";
    await tick();
    searchPanel?.focusSearch(text);
  }

  export function focusClasses() {
    side = "classes";
    tick().then(() => filterInput?.select());
  }

  async function saveClass() {
    if (!doc || tab.kind === "overview") return;
    const name = tab.kind === "manifest" ? "AndroidManifest.xml" : `${ws.className(tab.cls!)}.${tab.view === "smali" ? "smali" : "java"}`;
    const path = await pickSave({ defaultPath: name });
    if (!path) return;
    try {
      await writeTextFile(path, doc.lines.join("\n"));
      say(`Saved ${name}`);
    } catch (e) {
      say(`Couldn't save ${name}: ${errorMessage(e)}`, true);
    }
  }

  async function exportAll() {
    if (exporting) return;
    const dir = await pickPath({ directory: true, multiple: false, title: "Export sources to" });
    if (!dir) return;
    const ticket = `export-${Date.now()}`;
    exporting = { ticket, done: 0, total: ws.classes.length };
    setTask(`Exporting ${ws.name}`);
    try {
      const r = await exportSources(ws.session, dir, ticket);
      const failed = r.failed ? `, ${fmtN(r.failed)} failed (see the log)` : "";
      say(`Exported ${fmtN(r.written)} classes to ${r.dir}${failed}`, r.failed > 0);
      revealItemInDir(r.dir).catch(() => {});
    } catch (e) {
      const err = e as { code?: string };
      say(err.code === "CANCELLED" ? "Export cancelled" : `Export failed: ${errorMessage(e)}`, err.code !== "CANCELLED");
    } finally {
      exporting = null;
      setTask("");
    }
  }

  $effect(() => {
    const off = onEngineProgress((p) => {
      if (exporting && p.ticket === exporting.ticket) {
        exporting = { ...exporting, done: p.done, total: p.total };
        setTask(`Exporting ${fmtN(p.done)} of ${fmtN(p.total)} classes`);
      }
    });
    return () => void off.then((f) => f());
  });

  function zoom(by: number) {
    prefs.fontSize = by === 0 ? 13.5 : Math.max(10, Math.min(22, prefs.fontSize + by));
  }

  function pickDecompiler(e: MouseEvent) {
    const r = (e.currentTarget as HTMLElement).getBoundingClientRect();
    ctx = {
      x: r.left,
      y: r.bottom + 4,
      items: javaViews.map((v) => ({ label: v.view === "java" ? "jadx" : v.label, checked: tab.view === v.view, run: () => ws.setView(v.view) })),
    };
  }

  /* ---------- opening things from panels ---------- */

  function openUsage(u: Usage) {
    ws.openClass(u.cls, { view: "java", pos: { line: u.line, col: u.col }, mark: true });
  }

  function openHit(h: SearchHit) {
    if (isNameHit(h)) ws.openClass(h.node.top, { view: "java", node: h.node.id });
    else if (isFileHit(h)) ws.openFile(h.path, { pos: { line: h.line, col: h.col }, mark: true });
    else ws.openClass(h.cls, { view: "java", pos: { line: h.line, col: h.col }, mark: true });
  }

  function openNode(id: string) {
    const cls = ownerOf(id);
    const top = ws.classes.find((c) => cls === c.id || cls.startsWith(c.id + "$"))?.id;
    if (top) ws.openClass(top, { view: "java", node: id });
  }

  /* ---------- menus ---------- */

  function codeMenu(): (MenuItem | "-")[] {
    const t = target();
    const node = t?.node;
    const inClass = tab.kind === "class";
    const word = doc ? wordAt(doc.lines[tab.caret.line] ?? "", tab.caret.col) : null;
    const text = word && doc ? doc.lines[tab.caret.line].slice(word.start, word.end) : "";
    return [
      { label: "Go to declaration", key: "D", disabled: !node, run: goToDeclaration },
      { label: "Find usages", key: "X", disabled: !node, run: usages },
      "-",
      { label: "Rename", key: "N", disabled: !node || !inClass, run: rename },
      { label: "Comment", key: ";", disabled: !inClass, run: comment },
      { label: "Bookmark line", key: "Ctrl B", disabled: !inClass, run: bookmark },
      "-",
      { label: "Copy Frida snippet", key: "F", disabled: !inClass, run: frida },
      { label: "Copy Xposed snippet", key: "Y", disabled: !inClass, run: xposed },
      { label: node ? `Copy name ${node.name}` : "Copy name", disabled: !node, run: () => node && copy(node.name, node.name) },
      { label: "Copy smali reference", disabled: !node, run: () => node && copy(smaliRef(node), "the smali reference") },
      { label: "Copy class source", key: "Ctrl Shift C", disabled: !doc, run: copySource },
      "-",
      { label: text ? `Search for ${text.length > 24 ? text.slice(0, 24) : text}` : "Search", key: "Ctrl Shift F", run: () => openSearch() },
    ];
  }

  function onCodeContext(e: MouseEvent) {
    ctx = { x: e.clientX, y: e.clientY, items: codeMenu() };
  }

  function onTreeContext(e: MouseEvent, cls: string) {
    const node: NodeInfo = { kind: "class", id: cls, top: cls, name: ws.className(cls), detail: dotted(cls), access: "", static: false };
    ctx = {
      x: e.clientX,
      y: e.clientY,
      items: [
        { label: "Open", key: "Enter", run: () => ws.openClass(cls) },
        { label: "Open as smali", run: () => ws.openClass(cls, { view: "smali" }) },
        { label: "Find usages", run: () => ws.findUsages(node) },
        "-",
        { label: "Copy name", run: () => copy(dotted(cls), dotted(cls)) },
        { label: "Copy Frida snippet", run: () => copy(fridaSnippet(node), "a Frida snippet") },
        { label: "Copy Xposed snippet", run: () => copy(xposedSnippet(node), "an Xposed snippet") },
      ],
    };
  }

  /** The app bar's menus while a file is open; the page adds them after File. */
  export function menus(): Menu[] {
    const inCode = tab.kind !== "overview" && !!doc;
    return [
      {
        label: "Go",
        items: [
          { label: "Back", key: "Alt Left", disabled: !ws.back.length, run: () => ws.goBack() },
          { label: "Forward", key: "Alt Right", disabled: !ws.fwd.length, run: () => ws.goForward() },
          { label: "Overview", run: () => ws.showOverview() },
          { label: "Go to line", key: "Ctrl G", disabled: !inCode, run: goToLine },
          { label: "Find in class", key: "Ctrl F", disabled: !inCode, run: () => code?.openFind() },
          { label: "Search everything", key: "Ctrl Shift F", run: () => openSearch() },
        ],
      },
      {
        label: "Code",
        items: [
          { label: "Go to declaration", key: "D", disabled: !inCode, run: goToDeclaration },
          { label: "Find usages", key: "X", disabled: !inCode, run: usages },
          { label: "Rename", key: "N", disabled: tab.kind !== "class", run: rename },
          { label: "Comment", key: ";", disabled: tab.kind !== "class", run: comment },
          { label: "Bookmark line", key: "Ctrl B", disabled: tab.kind !== "class", run: bookmark },
          { label: "Copy Frida snippet", key: "F", disabled: tab.kind !== "class", run: frida },
          { label: "Copy Xposed snippet", key: "Y", disabled: tab.kind !== "class", run: xposed },
          { label: "Copy class source", key: "Ctrl Shift C", disabled: !doc || tab.kind === "overview", run: copySource },
          { label: tab.view === "smali" ? "Show Java" : isDex ? "Show smali" : "Show bytecode", key: "Tab", disabled: tab.kind !== "class", run: toggleView },
          ...(views.some((v) => v.view === "vineflower")
            ? [{ label: tab.view === "vineflower" ? "Show jadx output" : "Show Vineflower output", disabled: tab.kind !== "class", run: () => ws.setView(tab.view === "vineflower" ? "java" : "vineflower") }]
            : []),
        ],
      },
      {
        label: "View",
        items: [
          { label: "Classes", run: focusClasses },
          { label: "Files", run: () => (side = "files") },
          { label: "Notes", run: () => (side = "notes") },
          { label: "Search", key: "Ctrl Shift F", run: () => openSearch(false) },
          { label: prefs.inspector ? "Hide inspector" : "Show inspector", key: "Ctrl Alt I", run: toggleInspector },
          "-",
          { label: "Bigger text", key: "Ctrl =", run: () => zoom(1) },
          { label: "Smaller text", key: "Ctrl -", run: () => zoom(-1) },
          { label: "Reset text size", key: "Ctrl 0", run: () => zoom(0) },
        ],
      },
    ];
  }

  /** File menu items that only make sense with a file open. */
  export function fileItems(): MenuItem[] {
    return [
      ws.opened.deobfuscated
        ? { label: "Show original names", run: () => reopen(false) }
        : { label: "Use generated names", run: () => reopen(true) },
      { label: "Save class as", key: "Ctrl S", disabled: !doc || tab.kind === "overview", run: saveClass },
      exporting
        ? { label: "Cancel export", run: () => exporting && cancelJob(exporting.ticket) }
        : { label: "Export all sources", run: exportAll },
    ];
  }

  export function paletteItems(): PaletteItem[] {
    void ws.names;
    const items: PaletteItem[] = ws.classes.map((c) => ({
      section: "Classes",
      label: ws.className(c.id),
      sub: dotted(c.id.slice(0, Math.max(0, c.id.lastIndexOf("/")))),
      run: () => ws.openClass(c.id),
    }));
    if (doc && tab.kind === "class") {
      const seen = new Set<string>();
      for (const d of doc.declLines) {
        const n = doc.nodes[d.node];
        if (seen.has(n.id) || n.kind === "class") continue;
        seen.add(n.id);
        const pos = doc.decls.get(n.id)!;
        items.push({ section: `In ${ws.className(tab.cls!)}`, label: n.name, sub: n.detail, member: true, run: () => jump(pos) });
      }
    }
    return items;
  }

  /** Tab flips between Java and smali/bytecode; from Vineflower it goes back to jadx. */
  function toggleView() {
    if (tab.kind === "class") ws.setView(tab.view === "smali" ? "java" : tab.view === "java" ? "smali" : "java");
  }

  /* ---------- keys ---------- */

  function onkeydown(e: KeyboardEvent) {
    if (prompt || ctx || e.defaultPrevented) return;
    const mod = e.ctrlKey || e.metaKey;
    const el = e.target as HTMLElement;
    const typing = el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement;
    const inCode = !!el.closest?.(".code");
    const key = e.key.length === 1 ? e.key.toLowerCase() : e.key;

    if (e.altKey && !mod && (key === "ArrowLeft" || key === "ArrowRight")) {
      if (key === "ArrowLeft") ws.goBack();
      else ws.goForward();
    } else if (mod && e.shiftKey && key === "f") openSearch();
    else if (mod && !e.shiftKey && key === "w") ws.closeTab(ws.activeKey);
    else if (mod && key === "Tab") ws.cycleTab(e.shiftKey ? -1 : 1);
    else if (mod && key === "PageDown") ws.cycleTab(1);
    else if (mod && key === "PageUp") ws.cycleTab(-1);
    else if (mod && !e.shiftKey && key === "g") goToLine();
    else if (mod && !e.shiftKey && key === "s") saveClass();
    else if (mod && !e.shiftKey && key === "b") bookmark();
    else if (mod && (key === "=" || key === "+")) zoom(1);
    else if (mod && key === "-") zoom(-1);
    else if (mod && key === "0") zoom(0);
    else if (mod && e.shiftKey && key === "e") focusClasses();
    else if (mod && e.altKey && key === "i") toggleInspector();
    else if (mod && e.shiftKey && key === "c" && doc) copySource();
    else if (!typing && !mod && !e.altKey && inCode) {
      if (key === "d" || key === "Enter" || key === "F12") goToDeclaration();
      else if (key === "x") usages();
      else if (key === "n") rename();
      else if (key === ";") comment();
      else if (key === "f") frida();
      else if (key === "y") xposed();
      else if (key === "Tab" && !e.shiftKey) toggleView();
      else if (key === "Escape" && ws.usages) ws.usages = null;
      else if (key === "Escape") ws.goBack();
      else return;
    } else if (key === "Escape" && ws.usages && !typing) ws.usages = null;
    else return;
    e.preventDefault();
  }

  function onmouseup(e: MouseEvent) {
    // The side buttons on a mouse.
    if (e.button === 3) ws.goBack();
    else if (e.button === 4) ws.goForward();
  }

  /* ---------- resizing ---------- */

  function drag(e: PointerEvent, which: "side" | "usages") {
    const start = which === "side" ? prefs.side : prefs.usages;
    const x0 = e.clientX;
    const y0 = e.clientY;
    const el = e.currentTarget as HTMLElement;
    el.setPointerCapture(e.pointerId);
    const move = (m: PointerEvent) => {
      if (which === "side") prefs.side = Math.max(200, Math.min(560, start + m.clientX - x0));
      else prefs.usages = Math.max(120, Math.min(innerHeight - 260, start - (m.clientY - y0)));
    };
    const up = () => {
      el.removeEventListener("pointermove", move);
      el.removeEventListener("pointerup", up);
    };
    el.addEventListener("pointermove", move);
    el.addEventListener("pointerup", up);
  }

  // Focus the code when a class tab becomes active.
  $effect(() => {
    if (tab.kind === "class" && doc) untrack(() => tick().then(() => code?.focusCode()));
  });

  const fileName = (path: string) => path.slice(path.lastIndexOf("/") + 1);
  let imageSize = $state("");

  function tabLabel(t: Tab): string {
    if (t.kind === "overview") return "Overview";
    if (t.kind === "manifest") return "AndroidManifest.xml";
    if (t.kind === "file") return fileName(t.path!);
    return ws.className(t.cls!);
  }
</script>

<svelte:window {onkeydown} {onmouseup} />

{#snippet railButton(id: Side, icon: "listTree" | "files" | "search" | "bookmark", label: string, key: string)}
  <button class:on={side === id} title="{label} ({key})" aria-label={label} onclick={() => (id === "search" ? openSearch(false) : id === "classes" ? focusClasses() : (side = id))}>
    <Icon name={icon} size={20} />
    {#if id === "notes" && noteCount}<span class="badge">{noteCount}</span>{/if}
  </button>
{/snippet}

<div class="bench" class:insp={inspecting} style:--side="{prefs.side}px">
  <nav class="rail" aria-label="Panels">
    {@render railButton("classes", "listTree", "Classes", "Ctrl Shift E")}
    {@render railButton("files", "files", "Files", "View menu")}
    {@render railButton("search", "search", "Search", "Ctrl Shift F")}
    {@render railButton("notes", "bookmark", "Notes", "View menu")}
  </nav>

  <section class="island side">
    {#if side === "classes"}
      <div class="ph">
        <h2>Classes</h2>
        <span class="n">{fmtN(ws.classes.length)}</span>
        <span class="acts">
          <button class="ib" title="Collapse all" onclick={() => tree?.collapseAll()}><Icon name="fold" size={16} /></button>
          <button class="ib" title="Show the open class" onclick={() => ((filter = ""), tree?.revealCurrent())}><Icon name="target" size={16} /></button>
        </span>
      </div>
      <label class="field">
        <Icon name="filter" size={15} />
        <input
          bind:this={filterInput}
          bind:value={filter}
          placeholder="Filter classes"
          spellcheck="false"
          autocomplete="off"
          aria-label="Filter classes"
          onkeydown={(e) => {
            if (e.key === "ArrowDown" || e.key === "Enter") {
              e.preventDefault();
              tree?.focusTree();
            } else if (e.key === "Escape" && filter) {
              e.preventDefault();
              e.stopPropagation();
              filter = "";
            }
          }}
        />
        {#if filter}<button class="ib clear" title="Clear" onclick={() => (filter = "")}><Icon name="x" size={14} /></button>{/if}
      </label>
      {#key ws}
        <ClassTree
          bind:this={tree}
          {ws}
          {filter}
          current={tab.cls}
          {members}
          {memberAt}
          onopen={(cls) => ws.openClass(cls)}
          onmember={(m) => jump({ line: m.line, col: m.col })}
          oncontext={onTreeContext}
        />
      {/key}
    {:else if side === "files"}
      <FilesPanel {ws} current={tab.path} onopen={(p) => ws.openFile(p)} />
    {:else if side === "search"}
      <SearchPanel bind:this={searchPanel} {ws} onopen={openHit} onclose={() => (side = "classes")} />
    {:else}
      <NotesPanel {ws} onnode={openNode} onbookmark={(cls, line) => ws.openClass(cls, { pos: { line, col: 0 }, mark: true })} />
    {/if}
    <!-- svelte-ignore a11y_no_static_element_interactions -->
    <div class="grip" title="Drag to resize" onpointerdown={(e) => drag(e, "side")}></div>
  </section>

  <section class="island main">
    <div class="tabs" role="tablist">
      <div class="tablist">
        {#each ws.tabs as t (t.key)}
          {@const kind = t.cls ? ws.byId.get(t.cls)?.kind : undefined}
          <div
            class="tab"
            class:on={t.key === ws.activeKey}
            role="tab"
            tabindex="-1"
            aria-selected={t.key === ws.activeKey}
            title={t.cls ? dotted(t.cls) : (t.path ?? "")}
            onclick={() => ws.activate(t.key)}
            onkeydown={() => {}}
            onauxclick={(e) => e.button === 1 && ws.closeTab(t.key)}
          >
            {#if t.kind === "overview"}<Icon name="info" size={15} />
            {:else if t.kind === "class"}<span class="k {kind ? KIND_CLASS[kind] : 'k-c'}">{kind ? KIND_LETTER[kind] : "C"}</span>
            {:else}<Icon name="fileCode" size={15} />{/if}
            <span class="tl">{tabLabel(t)}</span>
            {#if t.kind === "class" && t.view !== "java"}<span class="vt">{views.find((v) => v.view === t.view)?.tag}</span>{/if}
            {#if t.kind !== "overview"}
              <button class="tx" title="Close (Ctrl W)" onclick={(e) => (e.stopPropagation(), ws.closeTab(t.key))}><Icon name="x" size={13} /></button>
            {/if}
          </div>
        {/each}
      </div>
      {#if tab.kind === "class"}
        <div class="views">
          <div class="seg" role="radiogroup" aria-label="View">
            <button role="radio" aria-checked={tab.view !== "smali"} class:on={tab.view !== "smali"} onclick={() => tab.view === "smali" && ws.setView("java")}>Java</button>
            <button role="radio" aria-checked={tab.view === "smali"} class:on={tab.view === "smali"} onclick={() => ws.setView("smali")}>{lowView.label}</button>
          </div>
          {#if javaViews.length > 1 && tab.view !== "smali"}
            <button class="pick" title="Decompiler" onclick={pickDecompiler}>{tab.view === "vineflower" ? "Vineflower" : "jadx"}<Icon name="chevronDown" size={14} /></button>
          {/if}
          {#if !prefs.inspector}
            <button class="ib" title="Show the inspector (Ctrl Alt I)" onclick={toggleInspector}><Icon name="layoutSidebarRight" size={16} /></button>
          {/if}
        </div>
      {/if}
    </div>

    <div class="editor">
      {#if tab.kind === "overview"}
        <OverviewPage {ws} {home} onfilter={(p) => ((side = "classes"), (filter = p))} onreopen={reopen} />
      {:else if doc}
        {#key `${tab.key}:${tab.view}`}
          <CodeView
            bind:this={code}
            {doc}
            caret={tab.caret}
            reveal={tab.reveal}
            top={tab.top}
            seen={tab.seen}
            fontSize={prefs.fontSize}
            notes={tab.kind === "class" ? notes : undefined}
            renamed={isRenamedNode}
            bookmarks={bookmarksHere}
            onfollow={follow}
            oncaret={(p) => (tab.caret = p)}
            oncontext={onCodeContext}
            onscrolled={(px) => (tab.top = px)}
            onrevealed={(n) => (tab.seen = n)}
          />
        {/key}
      {:else if entry?.state === "image"}
        <div class="image">
          <img src={entry.src} alt={fileName(tab.path ?? "")} onload={(e) => (imageSize = `${(e.currentTarget as HTMLImageElement).naturalWidth} x ${(e.currentTarget as HTMLImageElement).naturalHeight}`)} />
          <p>{imageSize}<span class="dim">{fmtSize(entry.size)}</span></p>
        </div>
      {:else if entry?.state === "error"}
        <div class="state err">
          <p>Couldn't {tab.kind === "file" ? "read" : "decompile"} {tab.kind === "file" ? fileName(tab.path ?? "") : ws.className(tab.cls ?? "")}</p>
          <pre>{entry.message}</pre>
          {#each views.filter((v) => v.view !== tab.view) as v (v.view)}
            <button class="lnk" onclick={() => ws.setView(v.view)}>Show {v.label} instead</button>
          {/each}
        </div>
      {:else}
        <div class="state"><i class="spin"></i>{tab.kind === "file" ? `Reading ${fileName(tab.path ?? "")}` : `Decompiling ${ws.className(tab.cls ?? "")}`}</div>
      {/if}
      {#if tab.kind === "file" && entry?.state === "ready" && entry.note}<p class="filenote">{entry.note}</p>{/if}
    </div>

    {#if ws.usages}
      <!-- svelte-ignore a11y_no_static_element_interactions -->
      <div class="split" onpointerdown={(e) => drag(e, "usages")}></div>
      <div class="usages" style:height="{prefs.usages}px">
        <UsagesPanel {ws} view={ws.usages} onopen={openUsage} onclose={() => (ws.usages = null)} />
      </div>
    {/if}
  </section>

  {#if inspecting}
    <Inspector
      {ws}
      {doc}
      {tab}
      onusage={openUsage}
      onallusages={(n) => ws.findUsages(n)}
      onjump={jump}
      onrename={rename}
      oncomment={comment}
      onbookmark={bookmark}
      oncopy={copy}
      onclose={toggleInspector}
    />
  {/if}
</div>

{#if ctx}
  <ContextMenu x={ctx.x} y={ctx.y} items={ctx.items} onclose={() => (ctx = null)} />
{/if}
{#if prompt}
  <Prompt req={prompt} onclose={() => (prompt = null)} />
{/if}

<style>
  .bench {
    flex: 1;
    min-height: 0;
    display: grid;
    grid-template-columns: 50px var(--side) minmax(0, 1fr);
    gap: 6px;
    padding-right: 6px;
  }
  .bench.insp {
    grid-template-columns: 50px var(--side) minmax(0, 1fr) 362px;
  }
  .rail {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 4px;
    padding-top: 2px;
  }
  .rail button {
    position: relative;
    width: 38px;
    height: 38px;
    display: grid;
    place-items: center;
    border-radius: 10px;
    border: 1px solid transparent;
    color: var(--text-3);
  }
  .rail button:hover {
    color: var(--text);
    background: var(--hover);
  }
  .rail button.on {
    color: var(--text-hi);
    background: var(--panel);
    border-color: var(--edge);
  }
  .badge {
    position: absolute;
    top: 3px;
    right: 2px;
    min-width: 16px;
    height: 16px;
    padding: 0 4px;
    border-radius: 8px;
    background: var(--ink);
    color: var(--on-accent);
    font: 600 10.5px/16px var(--font-ui);
  }
  .side {
    position: relative;
  }
  .grip {
    position: absolute;
    top: 0;
    bottom: 0;
    right: -5px;
    width: 8px;
    cursor: col-resize;
    z-index: 4;
  }
  .clear {
    width: 22px;
    height: 22px;
  }

  .main {
    background: var(--editor);
  }
  .tabs {
    height: 44px;
    flex: none;
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 0 6px;
    background: var(--tabs);
  }
  .tablist {
    flex: 1;
    min-width: 0;
    display: flex;
    align-items: center;
    gap: 2px;
    overflow-x: auto;
    scrollbar-width: none;
  }
  .tab {
    display: flex;
    align-items: center;
    gap: 8px;
    flex: none;
    max-width: 230px;
    height: 32px;
    padding: 0 6px 0 11px;
    border-radius: 8px;
    color: var(--text-3);
    cursor: pointer;
    white-space: nowrap;
  }
  .tab:hover {
    color: var(--text-2);
    background: var(--hover);
  }
  .tab.on {
    background: var(--editor);
    color: var(--text-hi);
    box-shadow: 0 0 0 1px var(--edge);
  }
  .tl {
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .vt {
    font: 11.5px var(--font-code);
    color: var(--text-3);
  }
  .tx {
    display: grid;
    place-items: center;
    width: 20px;
    height: 20px;
    border-radius: 5px;
    color: var(--text-3);
    visibility: hidden;
  }
  .tab:hover .tx,
  .tab.on .tx {
    visibility: visible;
  }
  .tx:hover {
    background: var(--hover);
    color: var(--text-hi);
  }
  .tab:not(:has(.tx)) {
    padding-right: 12px;
  }
  .views {
    display: flex;
    align-items: center;
    gap: 6px;
    flex: none;
  }
  .seg {
    display: flex;
    padding: 3px;
    border-radius: 9px;
    background: var(--frame);
  }
  .seg button {
    padding: 3px 11px;
    border-radius: 6px;
    color: var(--text-3);
  }
  .seg button:hover {
    color: var(--text);
  }
  .seg button.on {
    background: var(--panel);
    color: var(--text-hi);
    box-shadow: 0 0 0 1px var(--edge);
  }
  .pick {
    display: flex;
    align-items: center;
    gap: 5px;
    height: 30px;
    padding: 0 7px 0 10px;
    border-radius: 8px;
    color: var(--text-2);
  }
  .pick:hover {
    background: var(--hover);
    color: var(--text-hi);
  }

  .editor {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
  }
  .split {
    flex: none;
    height: 7px;
    margin-top: -3px;
    cursor: row-resize;
    position: relative;
    z-index: 4;
    border-bottom: 1px solid var(--line);
  }
  .usages {
    flex: none;
    min-height: 0;
  }
  .state {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 10px;
    padding: 28px 32px;
    color: var(--text-3);
  }
  .state:not(.err) {
    flex-direction: row;
    align-items: center;
  }
  .state p {
    margin: 0;
    color: var(--bad);
    font-weight: 500;
  }
  .state pre {
    margin: 0;
    max-width: 100%;
    white-space: pre-wrap;
    font: 12.5px var(--font-code);
    color: var(--text-2);
  }
  .lnk {
    color: var(--text-2);
    text-decoration: underline;
    text-underline-offset: 3px;
  }
  .lnk:hover {
    color: var(--text-hi);
  }
  .filenote {
    flex: none;
    margin: 0;
    padding: 8px 16px;
    border-top: 1px solid var(--line);
    color: var(--warn);
  }
  .image {
    flex: 1;
    min-width: 0;
    overflow: auto;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 14px;
    padding: 32px;
  }
  .image img {
    max-width: 100%;
    max-height: calc(100% - 40px);
    min-width: 32px;
    background: var(--panel);
    border-radius: 6px;
  }
  .image p {
    display: flex;
    gap: 12px;
    margin: 0;
    font: 12.5px var(--font-code);
    color: var(--text-2);
  }
  .dim {
    color: var(--text-3);
  }
</style>
