<script lang="ts">
  // The main window once a file is open: the class tree (or search, or notes)
  // on the left, tabs of decompiled classes in the middle, the class outline on
  // the right and usages underneath. Keyboard shortcuts follow jadx-gui.
  import { tick, untrack } from "svelte";
  import { open as pickPath, save as pickSave } from "@tauri-apps/plugin-dialog";
  import { revealItemInDir } from "@tauri-apps/plugin-opener";
  import { cancelJob, errorMessage, exportSources, isNameHit, onEngineProgress, writeTextFile, type NodeInfo, type SearchHit, type Usage } from "$lib/engine";
  import Icon from "$lib/Icon.svelte";
  import { fmtN, fmtSize } from "$lib/format";
  import { say, setTask } from "$lib/status.svelte";
  import type { Menu, MenuItem } from "$lib/shell/AppBar.svelte";
  import ContextMenu from "$lib/shell/ContextMenu.svelte";
  import Prompt, { type PromptRequest } from "$lib/shell/Prompt.svelte";
  import type { PaletteItem } from "$lib/shell/Palette.svelte";
  import ClassTree from "./ClassTree.svelte";
  import CodeView from "./CodeView.svelte";
  import FilesPanel from "./FilesPanel.svelte";
  import NotesPanel from "./NotesPanel.svelte";
  import Outline from "./Outline.svelte";
  import OverviewPage from "./OverviewPage.svelte";
  import SearchPanel from "./SearchPanel.svelte";
  import UsagesPanel from "./UsagesPanel.svelte";
  import { enclosing, linkAt, wordAt, type Link, type Pos, type View } from "./doc";
  import { fridaSnippet, ownerOf } from "./frida";
  import { dotted, simpleName, type Tab, type Workspace } from "./workspace.svelte";

  let { ws, home }: { ws: Workspace; home: string | null } = $props();

  type Side = "classes" | "files" | "search" | "notes";
  const PREFS = "jreverse.workbench";
  const IDENT = /^[A-Za-z_$][\w$]*$/;

  interface Prefs {
    side: number;
    outline: boolean;
    fontSize: number;
    usages: number;
  }
  const prefs: Prefs = $state({ side: 290, outline: true, fontSize: 13, usages: 230 });
  try {
    Object.assign(prefs, JSON.parse(localStorage.getItem(PREFS) ?? "{}"));
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
  let tree = $state<ClassTree>();
  let searchPanel = $state<SearchPanel>();
  let filterInput = $state<HTMLInputElement>();
  let ctx = $state<{ x: number; y: number; items: (MenuItem | "-")[] } | null>(null);
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

  untrack(() => ws.restore());

  /** For the status bar: where the caret is, and how this class came out. */
  export function where(): string[] {
    if (!doc || tab.kind === "overview") return [];
    const parts = [`Ln ${tab.caret.line + 1}, Col ${tab.caret.col + 1}`];
    if (tab.kind === "class" && tab.view !== "smali") {
      if (doc.warnings) parts.push(`${doc.warnings} ${doc.warnings === 1 ? "warning" : "warnings"}`);
      parts.push(`${doc.engine} ${doc.ms} ms`);
    }
    return parts;
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
    const original = node.kind === "class" ? simpleName(node.id) : node.id.slice(node.id.indexOf(".") + 1).split(/[(:]/)[0];
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
    prefs.fontSize = by === 0 ? 13 : Math.max(10, Math.min(22, prefs.fontSize + by));
  }

  /* ---------- opening things from panels ---------- */

  function openUsage(u: Usage) {
    ws.openClass(u.cls, { view: "java", pos: { line: u.line, col: u.col }, mark: true });
  }

  function openHit(h: SearchHit) {
    if (isNameHit(h)) ws.openClass(h.node.top, { view: "java", node: h.node.id });
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
      { label: node ? `Copy name ${node.name}` : "Copy name", disabled: !node, run: () => node && copy(node.name, node.name) },
      { label: "Copy smali reference", disabled: !node, run: () => node && copy(smaliRef(node), "the smali reference") },
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
          { label: prefs.outline ? "Hide outline" : "Show outline", run: () => (prefs.outline = !prefs.outline) },
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
    else if (!typing && !mod && !e.altKey && inCode) {
      if (key === "d" || key === "Enter" || key === "F12") goToDeclaration();
      else if (key === "x") usages();
      else if (key === "n") rename();
      else if (key === ";") comment();
      else if (key === "f") frida();
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

<div class="bench" style:--side="{prefs.side}px">
  <aside class="side">
    <div class="modes" role="tablist">
      <button role="tab" aria-selected={side === "classes"} class:on={side === "classes"} onclick={() => (side = "classes")}>Classes</button>
      <button role="tab" aria-selected={side === "files"} class:on={side === "files"} onclick={() => (side = "files")}>Files</button>
      <button role="tab" aria-selected={side === "search"} class:on={side === "search"} onclick={() => openSearch(false)}>Search</button>
      <button role="tab" aria-selected={side === "notes"} class:on={side === "notes"} onclick={() => (side = "notes")}>
        Notes{#if Object.keys(ws.project.renames).length + Object.keys(ws.project.comments).length + ws.project.bookmarks.length}<span class="badge">{Object.keys(ws.project.renames).length + Object.keys(ws.project.comments).length + ws.project.bookmarks.length}</span>{/if}
      </button>
    </div>
    {#if side === "classes"}
      <label class="filter">
        <Icon name="search" size={14} />
        <input
          bind:this={filterInput}
          bind:value={filter}
          placeholder="Filter {fmtN(ws.classes.length)} classes"
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
        {#if filter}<button class="clear" title="Clear" onclick={() => (filter = "")}><Icon name="x" size={13} /></button>{/if}
      </label>
      {#key ws}
        <ClassTree bind:this={tree} {ws} {filter} current={tab.cls} onopen={(cls) => ws.openClass(cls)} oncontext={onTreeContext} />
      {/key}
    {:else if side === "files"}
      <FilesPanel {ws} current={tab.path} onopen={(p) => ws.openFile(p)} />
    {:else if side === "search"}
      <SearchPanel bind:this={searchPanel} {ws} onopen={openHit} />
    {:else}
      <NotesPanel {ws} onnode={openNode} onbookmark={(cls, line) => ws.openClass(cls, { pos: { line, col: 0 }, mark: true })} />
    {/if}
  </aside>
  <!-- svelte-ignore a11y_no_static_element_interactions -->
  <div class="split v" onpointerdown={(e) => drag(e, "side")}></div>

  <div class="main">
    <div class="tabs" role="tablist">
      {#each ws.tabs as t (t.key)}
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
          <span class="tl" class:renamed={!!t.cls && ws.isRenamed(t.cls)}>{tabLabel(t)}</span>
          {#if t.kind === "class" && t.view !== "java"}<span class="tv">{views.find((v) => v.view === t.view)?.tag}</span>{/if}
          {#if t.kind !== "overview"}
            <button class="tx" title="Close (Ctrl W)" onclick={(e) => (e.stopPropagation(), ws.closeTab(t.key))}><Icon name="x" size={12} /></button>
          {/if}
        </div>
      {/each}
      <div class="tabfill"></div>
      <button class="ib" class:on={prefs.outline} title={prefs.outline ? "Hide outline" : "Show outline"} onclick={() => (prefs.outline = !prefs.outline)}>
        <Icon name="layoutSidebarRight" size={15} />
      </button>
    </div>

    <div class="editor">
      {#if tab.kind === "overview"}
        <OverviewPage {ws} {home} onfilter={(p) => ((side = "classes"), (filter = p))} />
      {:else}
        <div class="crumbs">
          {#if tab.kind === "manifest"}
            <span class="cn">AndroidManifest.xml</span>
          {:else if tab.kind === "file"}
            {#if tab.path!.includes("/")}<span class="pk">{tab.path!.slice(0, tab.path!.lastIndexOf("/"))}</span>{/if}
            <span class="cn">{fileName(tab.path!)}</span>
            {#if entry?.state === "ready" && entry.note}<span class="note">{entry.note}</span>{/if}
          {:else}
            {#if pkg}<span class="pk">{pkg}</span>{/if}
            <span class="cn">{ws.className(tab.cls!)}</span>
            {#if ws.isRenamed(tab.cls!)}<span class="pk">{simpleName(tab.cls!)}</span>{/if}
            <div class="seg" role="radiogroup" aria-label="View">
              {#each views as v (v.view)}
                <button role="radio" aria-checked={tab.view === v.view} class:on={tab.view === v.view} onclick={() => ws.setView(v.view)}>{v.label}</button>
              {/each}
            </div>
          {/if}
        </div>
        <div class="codewrap">
          {#if doc}
            <CodeView
              bind:this={code}
              {doc}
              caret={tab.caret}
              reveal={tab.reveal}
              fontSize={prefs.fontSize}
              onfollow={follow}
              oncaret={(p) => (tab.caret = p)}
              oncontext={onCodeContext}
            />
            {#if prefs.outline && tab.kind === "class" && doc.declLines.length}
              <aside class="outline">
                <div class="oh">Outline</div>
                <Outline {doc} caret={tab.caret} onjump={jump} />
              </aside>
            {/if}
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
        </div>
      {/if}
    </div>

    {#if ws.usages}
      <!-- svelte-ignore a11y_no_static_element_interactions -->
      <div class="split h" onpointerdown={(e) => drag(e, "usages")}></div>
      <div class="usages" style:height="{prefs.usages}px">
        <UsagesPanel {ws} view={ws.usages} onopen={openUsage} onclose={() => (ws.usages = null)} />
      </div>
    {/if}
  </div>
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
    grid-template-columns: var(--side) 0 minmax(0, 1fr);
  }
  .side {
    display: flex;
    flex-direction: column;
    min-height: 0;
    min-width: 0;
    background: var(--side);
  }
  .split {
    position: relative;
    z-index: 4;
  }
  .split.v {
    cursor: col-resize;
    width: 0;
    border-left: 1px solid var(--line);
  }
  .split.v::after {
    content: "";
    position: absolute;
    inset: 0 -4px;
  }
  .split.h {
    flex: none;
    height: 0;
    cursor: row-resize;
    border-top: 1px solid var(--line);
  }
  .split.h::after {
    content: "";
    position: absolute;
    inset: -4px 0;
  }
  .modes {
    display: flex;
    gap: 2px;
    height: 36px;
    flex: none;
    align-items: center;
    padding: 0 8px;
    border-bottom: 1px solid var(--line);
  }
  .modes button {
    display: flex;
    align-items: center;
    gap: 6px;
    height: 26px;
    padding: 0 9px;
    border-radius: 5px;
    font-size: 12.5px;
    color: var(--muted);
  }
  .modes button:hover {
    color: var(--text);
  }
  .modes button.on {
    color: var(--text-hi);
    background: var(--lift);
  }
  .badge {
    font: 10.5px var(--font-code);
    color: var(--text-3);
  }
  .filter {
    display: flex;
    align-items: center;
    gap: 8px;
    margin: 8px 10px 6px;
    height: 28px;
    padding: 0 6px 0 9px;
    border-radius: 6px;
    background: var(--pane);
    color: var(--text-3);
    box-shadow: inset 0 0 0 1px var(--line-2);
  }
  .filter:focus-within {
    box-shadow: inset 0 0 0 1px var(--beam-shade);
  }
  .filter input {
    flex: 1;
    min-width: 0;
    border: 0;
    outline: none;
    background: transparent;
    font-size: 12.5px;
    color: var(--text-hi);
  }
  .filter input::placeholder {
    color: var(--text-3);
  }
  .clear {
    display: grid;
    place-items: center;
    width: 20px;
    height: 20px;
    border-radius: 4px;
    color: var(--text-3);
  }
  .clear:hover {
    color: var(--text-hi);
    background: var(--lift-2);
  }

  .main {
    display: flex;
    flex-direction: column;
    min-width: 0;
    min-height: 0;
  }
  .tabs {
    display: flex;
    align-items: stretch;
    height: 36px;
    flex: none;
    background: var(--ground);
    border-bottom: 1px solid var(--line);
    overflow-x: auto;
    scrollbar-width: none;
  }
  .tab {
    display: flex;
    align-items: center;
    gap: 8px;
    max-width: 220px;
    padding: 0 6px 0 14px;
    border-right: 1px solid var(--line);
    font-size: 12.5px;
    color: var(--muted);
    cursor: pointer;
    white-space: nowrap;
    user-select: none;
  }
  .tab:hover {
    color: var(--text);
  }
  .tab.on {
    background: var(--pane);
    color: var(--text-hi);
    margin-bottom: -1px;
  }
  .tl {
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .tl.renamed {
    font-style: italic;
  }
  .tv {
    font: 10.5px var(--font-code);
    color: var(--text-3);
  }
  .tx {
    display: grid;
    place-items: center;
    width: 20px;
    height: 20px;
    border-radius: 4px;
    color: var(--faint);
    opacity: 0;
  }
  .tab:hover .tx,
  .tab.on .tx {
    opacity: 1;
  }
  .tx:hover {
    background: var(--lift-2);
    color: var(--text-hi);
  }
  .tab:not(:has(.tx)) {
    padding-right: 14px;
  }
  .tabfill {
    flex: 1;
  }
  .ib {
    flex: none;
    width: 36px;
    display: grid;
    place-items: center;
    color: var(--faint);
  }
  .ib:hover,
  .ib.on {
    color: var(--text-2);
  }

  .editor {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
    background: var(--pane);
  }
  .crumbs {
    display: flex;
    align-items: center;
    gap: 10px;
    height: 34px;
    flex: none;
    padding: 0 10px 0 16px;
    border-bottom: 1px solid var(--line);
    font-size: 12.5px;
    white-space: nowrap;
  }
  .pk {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    font: 11.5px var(--font-code);
    color: var(--text-3);
  }
  .cn {
    font-weight: 600;
    color: var(--text-hi);
  }
  .seg {
    margin-left: auto;
    display: flex;
    padding: 2px;
    border-radius: 6px;
    background: var(--ground);
    box-shadow: inset 0 0 0 1px var(--line);
  }
  .seg button {
    height: 22px;
    padding: 0 10px;
    border-radius: 4px;
    font-size: 12px;
    color: var(--muted);
  }
  .seg button.on {
    background: var(--lift-2);
    color: var(--text-hi);
  }
  .codewrap {
    flex: 1;
    min-width: 0;
    min-height: 0;
    display: flex;
  }
  .outline {
    width: 260px;
    flex: none;
    display: flex;
    flex-direction: column;
    min-height: 0;
    background: var(--side);
    border-left: 1px solid var(--line);
  }
  .oh {
    padding: 10px 12px 4px;
    font-size: 11.5px;
    font-weight: 600;
    color: var(--text-3);
  }
  @media (max-width: 1280px) {
    .outline {
      display: none;
    }
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
    color: var(--error);
    font-weight: 500;
  }
  .state pre {
    margin: 0;
    max-width: 100%;
    white-space: pre-wrap;
    font: 12px var(--font-code);
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
  .usages {
    flex: none;
    min-height: 0;
  }
  .note {
    font-size: 12px;
    color: var(--obf);
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
    background: var(--shelf);
    border-radius: 4px;
  }
  .image p {
    display: flex;
    gap: 12px;
    margin: 0;
    font: 12px var(--font-code);
    color: var(--text-2);
  }
  .dim {
    color: var(--text-3);
  }
</style>
