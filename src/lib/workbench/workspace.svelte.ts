// Everything about one opened file: tabs, decompiled documents, where you've
// been, and what you added (renames, comments, bookmarks), saved per file.
import { SvelteMap } from "svelte/reactivity";
import {
  decompileClass,
  errorMessage,
  findUsages,
  listFiles,
  readFile,
  loadProject,
  nodeInfo,
  overview,
  saveProject,
  setCodeData,
  smaliClass,
  type ClassEntry,
  type FileEntry,
  type NodeInfo,
  type Opened,
  type Overview,
  type Project,
  type Usage,
} from "$lib/engine";
import { say, setTask } from "$lib/status.svelte";
import { enclosing, findDecl, hexDoc, javaDoc, plainDoc, smaliDoc, xmlDoc, type Doc, type Pos, type View } from "./doc";
import { fmtN, fmtSize } from "$lib/format";
import { ownerOf } from "./hooks";
import { buildTree, type Pkg } from "./tree";
import type { Reveal } from "./CodeView.svelte";

export type TabKind = "overview" | "class" | "manifest" | "file";

export interface Tab {
  key: string;
  kind: TabKind;
  /** For class tabs. */
  cls?: string;
  /** For file tabs: the path inside the archive. */
  path?: string;
  view: View;
  caret: Pos;
  reveal: Reveal | null;
  /** The code view's scroll position, to come back to. */
  top?: number;
  /** The last `reveal.n` the code view has scrolled to. */
  seen?: number;
}

export type DocEntry =
  | { state: "loading" }
  | { state: "ready"; doc: Doc; note?: string }
  | { state: "image"; src: string; size: number }
  | { state: "error"; message: string };

interface Loc {
  key: string;
  cls?: string;
  view: View;
  pos: Pos;
}

export interface UsagesView {
  target: NodeInfo;
  state: "loading" | "ready" | "error";
  usages: Usage[];
  message?: string;
  ms?: number;
}

const MAX_TABS = 16;
const OVERVIEW = "overview";
const MANIFEST = "manifest";
const empty = (): Project => ({ renames: {}, comments: {}, bookmarks: [] });

export const dotted = (id: string) => id.replaceAll("/", ".");
export const simpleName = (id: string) => {
  const s = id.slice(id.lastIndexOf("/") + 1);
  return s.slice(s.lastIndexOf("$") + 1);
};

export class Workspace {
  readonly path: string;
  readonly name: string;
  readonly opened: Opened;
  readonly classes: ClassEntry[];
  readonly byId: Map<string, ClassEntry>;
  readonly tree: Pkg;

  project = $state<Project>(empty());
  tabs = $state<Tab[]>([{ key: OVERVIEW, kind: "overview", view: "java", caret: { line: 0, col: 0 }, reveal: null }]);
  activeKey = $state(OVERVIEW);
  readonly docs = new SvelteMap<string, DocEntry>();
  back = $state<Loc[]>([]);
  fwd = $state<Loc[]>([]);
  usages = $state<UsagesView | null>(null);
  info = $state<Overview | null>(null);
  infoError = $state<string | null>(null);
  /** Bumped whenever renames change, so views that show names recompute. */
  names = $state(0);
  /** Everything in the archive that isn't code; loaded when the Files panel first opens. */
  files = $state<FileEntry[] | null>(null);
  filesError = $state<string | null>(null);

  private pending = new Map<string, Promise<Doc>>();
  private revealN = 0;
  private saveTimer: ReturnType<typeof setTimeout> | undefined;
  private usageToken = 0;

  constructor(path: string, name: string, opened: Opened, classes: ClassEntry[]) {
    this.path = path;
    this.name = name;
    this.opened = opened;
    this.classes = classes;
    this.byId = new Map(classes.map((c) => [c.id, c]));
    this.tree = buildTree(classes);
  }

  get session() {
    return this.opened.session;
  }

  get active(): Tab {
    return this.tabs.find((t) => t.key === this.activeKey) ?? this.tabs[0];
  }

  /** Loads the saved project, applies its renames and comments, and reopens its tabs. */
  async restore() {
    overview(this.session).then(
      (o) => (this.info = o),
      (e) => (this.infoError = errorMessage(e)),
    );
    let saved: Project | null = null;
    try {
      saved = await loadProject(this.path);
    } catch (e) {
      say(`Couldn't read your notes for ${this.name}: ${errorMessage(e)}`, true);
    }
    if (!saved) return;
    this.project = { ...empty(), ...saved };
    if (Object.keys(this.project.renames).length || Object.keys(this.project.comments).length) {
      await this.pushCodeData();
    }
    // Tabs come back without decompiling; each loads when it's first shown.
    const views = new Set<View>(["java", "smali", ...(this.opened.engines.includes("vineflower") ? ["vineflower" as const] : [])]);
    for (const t of saved.tabs ?? []) {
      if (!this.byId.has(t.cls) || this.tabs.some((x) => x.cls === t.cls)) continue;
      const pos = { line: t.line, col: 0 };
      this.tabs.push({
        key: `class:${t.cls}`,
        kind: "class",
        cls: t.cls,
        view: views.has(t.view) ? t.view : "java",
        caret: pos,
        reveal: { ...pos, n: ++this.revealN },
      });
    }
    if (saved.active && this.tabs.some((t) => t.key === saved!.active)) this.activeKey = saved.active;
    if (this.active.cls) this.ensure(this.active);
  }

  /* ---------- names ---------- */

  /** The name to show for a class, after renames. */
  className(id: string): string {
    void this.names;
    return this.project.renames[id] ?? this.byId.get(id)?.name ?? simpleName(id);
  }

  isRenamed(id: string): boolean {
    void this.names;
    return id in this.project.renames;
  }

  /* ---------- documents ---------- */

  docKey(cls: string, view: View) {
    return `${view}:${cls}`;
  }

  doc(tab: Tab): DocEntry | undefined {
    if (tab.kind === "manifest") return this.docs.get(MANIFEST);
    if (tab.kind === "file") return this.docs.get(tab.key);
    return tab.cls ? this.docs.get(this.docKey(tab.cls, tab.view)) : undefined;
  }

  /** `fresh` decompiles again, still showing the old text until the new one is in. */
  loadDoc(cls: string, view: View, fresh = false): Promise<Doc> {
    const key = this.docKey(cls, view);
    const entry = this.docs.get(key);
    if (entry?.state === "ready" && !fresh) return Promise.resolve(entry.doc);
    const running = this.pending.get(key);
    if (running && !fresh) return running;
    if (entry?.state !== "ready") this.docs.set(key, { state: "loading" });
    setTask(`Decompiling ${simpleName(cls)}`);
    const fetch = async (): Promise<Doc> => {
      if (view === "java") return javaDoc(await decompileClass(this.session, cls));
      if (view === "vineflower") return javaDoc(await decompileClass(this.session, cls, "vineflower"));
      const s = await smaliClass(this.session, cls);
      return smaliDoc(cls, s.source, s.ms, (id) => this.byId.has(id));
    };
    const p = fetch()
      .then((doc) => {
        this.docs.set(key, { state: "ready", doc });
        return doc;
      })
      .catch((e) => {
        this.docs.set(key, { state: "error", message: errorMessage(e) });
        throw e;
      })
      .finally(() => {
        this.pending.delete(key);
        if (!this.pending.size) setTask("");
      });
    this.pending.set(key, p);
    return p;
  }

  manifestDoc(): Doc | null {
    const text = this.info?.manifest;
    if (!text) return null;
    const entry = this.docs.get(MANIFEST);
    if (entry?.state === "ready") return entry.doc;
    const doc = xmlDoc(text, this.info?.android?.package, (id) => this.byId.has(id));
    this.docs.set(MANIFEST, { state: "ready", doc });
    return doc;
  }

  /** Makes sure a tab's document is loading or loaded; errors show in the tab. */
  ensure(tab: Tab) {
    if (tab.kind === "class" && tab.cls && !this.doc(tab)) this.loadDoc(tab.cls, tab.view).catch(() => {});
    if (tab.kind === "file" && tab.path && !this.doc(tab)) this.loadFile(tab.key, tab.path);
  }

  /* ---------- files ---------- */

  async loadFiles() {
    if (this.files || this.filesError) return;
    try {
      this.files = (await listFiles(this.session)).files;
    } catch (e) {
      this.filesError = errorMessage(e);
    }
  }

  /**
   * Opens a file from the archive in a tab. resources.arsc doesn't get one:
   * its decoded res/values files join the list instead.
   */
  async openFile(path: string, o: { pos?: Pos; mark?: boolean } = {}) {
    if (this.files?.find((f) => f.path === path)?.type === "arsc") return this.expandTable(path);
    const key = `file:${path}`;
    if (!this.tabs.some((t) => t.key === key)) {
      const at = this.tabs.findIndex((t) => t.key === this.activeKey);
      this.tabs.splice(at < 0 ? this.tabs.length : at + 1, 0, { key, kind: "file", path, view: "java", caret: { line: 0, col: 0 }, reveal: null });
    }
    this.activate(key);
    await this.loadFile(key, path);
    const tab = this.tabs.find((t) => t.key === key);
    if (tab && o.pos) this.place(tab, o.pos, o.mark);
  }

  private async loadFile(key: string, path: string) {
    if (this.docs.has(key)) return;
    this.docs.set(key, { state: "loading" });
    try {
      const c = await readFile(this.session, path);
      if (c.kind === "image") {
        this.docs.set(key, { state: "image", src: `data:${c.mime};base64,${c.data}`, size: c.size });
      } else if (c.kind === "binary") {
        const bytes = Uint8Array.from(atob(c.data ?? ""), (ch) => ch.charCodeAt(0));
        const note = c.truncated ? `First ${fmtSize(bytes.length)} of ${fmtSize(c.size)}` : undefined;
        this.docs.set(key, { state: "ready", doc: hexDoc(bytes), note });
      } else if (c.kind === "text") {
        const text = c.text ?? "";
        const xml = /\.xml$/i.test(path) || text.startsWith("<?xml");
        const doc = xml ? xmlDoc(text, this.info?.android?.package, (id) => this.byId.has(id)) : plainDoc(text);
        this.docs.set(key, { state: "ready", doc, note: c.truncated ? `First ${fmtSize(text.length)}` : undefined });
      } else {
        this.docs.delete(key);
      }
    } catch (e) {
      this.docs.set(key, { state: "error", message: errorMessage(e) });
    }
  }

  private async expandTable(path: string) {
    setTask("Decoding resources.arsc");
    try {
      const c = await readFile(this.session, path);
      const have = new Set(this.files?.map((f) => f.path));
      const added = (c.children ?? []).filter((p) => !have.has(p)).map((p): FileEntry => ({ path: p, type: "xml", size: -1 }));
      this.files = [...(this.files ?? []), ...added];
      say(added.length ? `Decoded ${fmtN(added.length)} files from resources.arsc into res/` : "resources.arsc holds no values");
    } catch (e) {
      say(`Couldn't decode resources.arsc: ${errorMessage(e)}`, true);
    } finally {
      setTask("");
    }
  }

  /* ---------- tabs and navigation ---------- */

  private here(): Loc {
    const t = this.active;
    return { key: t.key, cls: t.cls, view: t.view, pos: { ...t.caret } };
  }

  private record() {
    const loc = this.here();
    const last = this.back[this.back.length - 1];
    if (last && last.key === loc.key && last.pos.line === loc.pos.line) return;
    this.back.push(loc);
    if (this.back.length > 200) this.back.shift();
    this.fwd = [];
  }

  activate(key: string, record = true) {
    if (key === this.activeKey) return;
    if (record) this.record();
    this.activeKey = key;
    this.ensure(this.active);
    this.persist();
  }

  showOverview() {
    this.activate(OVERVIEW);
  }

  showManifest() {
    if (!this.manifestDoc()) return;
    if (!this.tabs.some((t) => t.key === MANIFEST)) {
      this.tabs.splice(1, 0, { key: MANIFEST, kind: "manifest", view: "java", caret: { line: 0, col: 0 }, reveal: null });
    }
    this.activate(MANIFEST);
  }

  /**
   * Opens a class in its tab (reusing it if open). `node` puts the caret on that
   * member's declaration, `pos` on a line and column.
   */
  async openClass(
    cls: string,
    o: { view?: View; node?: string; pos?: Pos; mark?: boolean; record?: boolean; activate?: boolean } = {},
  ): Promise<void> {
    const top = this.byId.has(cls) ? cls : (this.classes.find((c) => cls.startsWith(c.id + "$"))?.id ?? cls);
    let tab = this.tabs.find((t) => t.kind === "class" && t.cls === top);
    if (o.record !== false && o.activate !== false) this.record();
    if (!tab) {
      tab = { key: `class:${top}`, kind: "class", cls: top, view: o.view ?? "java", caret: { line: 0, col: 0 }, reveal: null };
      const classTabs = this.tabs.filter((t) => t.kind === "class");
      if (classTabs.length >= MAX_TABS) {
        const drop = classTabs.find((t) => t.key !== this.activeKey);
        if (drop) this.tabs.splice(this.tabs.indexOf(drop), 1);
      }
      const at = this.tabs.findIndex((t) => t.key === this.activeKey);
      this.tabs.splice(at < 0 ? this.tabs.length : at + 1, 0, tab);
      tab = this.tabs.find((t) => t.key === `class:${top}`)!;
    } else if (o.view && o.view !== tab.view) {
      tab.view = o.view;
    }
    if (o.activate !== false) this.activeKey = tab.key;
    this.persist();
    let doc: Doc;
    try {
      doc = await this.loadDoc(top, tab.view);
    } catch (e) {
      say(`Couldn't decompile ${dotted(top)}: ${errorMessage(e)}`, true);
      return;
    }
    let pos = o.pos;
    if (o.node) pos = this.declIn(doc, o.node) ?? pos;
    if (!pos && cls !== top) pos = this.declIn(doc, cls);
    if (pos) this.place(tab, pos, o.mark);
  }

  /** Where `id` (or failing that, its class) is declared in `doc`. */
  private declIn(doc: Doc, id: string): Pos | undefined {
    const kind = id.includes("(") ? "method" : id.includes(".") ? "field" : "class";
    const stub = { kind, id, top: "", name: "", detail: "", access: "", static: false } as const;
    return findDecl(doc, stub) ?? (id.includes(".") ? findDecl(doc, { ...stub, kind: "class", id: ownerOf(id) }) : undefined);
  }

  private place(tab: Tab, pos: Pos, mark = false) {
    tab.caret = { ...pos };
    tab.reveal = { line: pos.line, col: pos.col, n: ++this.revealN, mark };
  }

  /** Follows a link: to the node's declaration, wherever it lives. */
  async goToNode(node: NodeInfo) {
    let top = node.top;
    if (!top) {
      try {
        top = (await nodeInfo(this.session, node.id)).top;
      } catch (e) {
        say(`Can't find ${node.name}: ${errorMessage(e)}`, true);
        return;
      }
    }
    if (!this.byId.has(top)) {
      say(`${dotted(top)} isn't in this file`);
      return;
    }
    await this.openClass(top, { node: node.id, view: this.active.kind === "class" ? this.active.view : "java" });
  }

  closeTab(key: string) {
    const i = this.tabs.findIndex((t) => t.key === key);
    if (i < 0 || this.tabs[i].kind === "overview") return;
    this.tabs.splice(i, 1);
    if (this.activeKey === key) {
      this.activeKey = (this.tabs[i] ?? this.tabs[i - 1] ?? this.tabs[0]).key;
      this.ensure(this.active);
    }
    this.back = this.back.filter((l) => l.key !== key);
    this.fwd = this.fwd.filter((l) => l.key !== key);
    this.persist();
  }

  cycleTab(by: 1 | -1) {
    const i = this.tabs.findIndex((t) => t.key === this.activeKey);
    this.activate(this.tabs[(i + by + this.tabs.length) % this.tabs.length].key, false);
  }

  /** Switches the active class tab between Java and smali, staying on the same member. */
  async setView(view: View) {
    const tab = this.active;
    if (tab.kind !== "class" || !tab.cls || tab.view === view) return;
    const entry = this.doc(tab);
    const from = entry?.state === "ready" ? enclosing(entry.doc, tab.caret.line) : undefined;
    tab.view = view;
    tab.top = undefined;
    this.persist();
    let doc: Doc;
    try {
      doc = await this.loadDoc(tab.cls, view);
    } catch {
      return;
    }
    const pos = (from && this.declIn(doc, from.id)) || { line: 0, col: 0 };
    this.place(tab, pos);
  }

  private async go(from: Loc[], to: Loc[]) {
    const loc = from.pop();
    if (!loc) return;
    to.push(this.here());
    if (loc.cls) await this.openClass(loc.cls, { view: loc.view, pos: loc.pos, record: false });
    else this.activeKey = this.tabs.some((t) => t.key === loc.key) ? loc.key : OVERVIEW;
  }

  goBack() {
    return this.go(this.back, this.fwd);
  }

  goForward() {
    return this.go(this.fwd, this.back);
  }

  /* ---------- usages ---------- */

  async findUsages(target: NodeInfo) {
    const token = ++this.usageToken;
    this.usages = { target, state: "loading", usages: [] };
    try {
      const r = await findUsages(this.session, target.id);
      if (token !== this.usageToken) return;
      this.usages = { target, state: "ready", usages: r.usages, ms: r.ms };
    } catch (e) {
      if (token !== this.usageToken) return;
      this.usages = { target, state: "error", usages: [], message: errorMessage(e) };
    }
  }

  /* ---------- renames, comments, bookmarks ---------- */

  async rename(node: Pick<NodeInfo, "id" | "name">, name: string | null) {
    if (name) this.project.renames[node.id] = name;
    else delete this.project.renames[node.id];
    await this.codeDataChanged(name ? `Renamed ${node.name} to ${name}` : `Restored the name of ${node.name}`);
  }

  async comment(node: Pick<NodeInfo, "id" | "name">, text: string | null) {
    if (text) this.project.comments[node.id] = text;
    else delete this.project.comments[node.id];
    await this.codeDataChanged(text ? `Commented ${node.name}` : `Removed the comment on ${node.name}`);
  }

  toggleBookmark(cls: string, line: number, note: string) {
    const i = this.project.bookmarks.findIndex((b) => b.cls === cls && b.line === line);
    if (i >= 0) this.project.bookmarks.splice(i, 1);
    else this.project.bookmarks.push({ cls, line, note });
    this.persist();
    say(i >= 0 ? "Removed the bookmark" : "Bookmarked the line");
  }

  private async pushCodeData() {
    await setCodeData(this.session, $state.snapshot(this.project.renames), $state.snapshot(this.project.comments));
    this.names++;
  }

  private async codeDataChanged(message: string) {
    this.persist();
    setTask("Applying");
    try {
      await this.pushCodeData();
    } catch (e) {
      say(`Couldn't apply it: ${errorMessage(e)}`, true);
      return;
    } finally {
      setTask("");
    }
    // Every Java view may show the changed name, so all of them are stale. Smali never changes.
    const shown = new Set(this.tabs.filter((t) => t.kind === "class" && t.view === "java").map((t) => this.docKey(t.cls!, "java")));
    for (const key of [...this.docs.keys()]) if (key.startsWith("java:") && !shown.has(key)) this.docs.delete(key);
    // Comments and "renamed from" notes add lines, so keep each caret where it was relative to its member.
    const anchors = this.tabs
      .filter((t) => t.kind === "class" && t.view === "java")
      .map((t) => {
        const e = this.doc(t);
        const at = e?.state === "ready" ? enclosing(e.doc, t.caret.line) : undefined;
        const decl = at && e?.state === "ready" ? e.doc.decls.get(at.id) : undefined;
        return { t, id: at?.id, delta: decl ? t.caret.line - decl.line : 0 };
      });
    const reloads = [...shown].map((key) => this.loadDoc(key.slice(5), "java", true).catch(() => {}));
    await Promise.all(reloads);
    for (const { t, id, delta } of anchors) {
      const e = this.doc(t);
      const decl = id && e?.state === "ready" ? e.doc.decls.get(id) : undefined;
      if (decl) t.caret = { line: decl.line + delta, col: t.caret.col };
    }
    if (this.usages) this.findUsages(this.usages.target);
    say(message);
  }

  /** Saves the project a moment after the last change. */
  persist() {
    clearTimeout(this.saveTimer);
    this.saveTimer = setTimeout(() => this.save(), 400);
  }

  async save() {
    clearTimeout(this.saveTimer);
    const p = $state.snapshot(this.project);
    const tabs = this.tabs.filter((t) => t.kind === "class").map((t) => ({ cls: t.cls!, view: t.view, line: t.caret.line }));
    const hasNotes = Object.keys(p.renames).length || Object.keys(p.comments).length || p.bookmarks.length || p.deobfuscate;
    try {
      await saveProject(this.path, hasNotes || tabs.length ? { ...p, tabs, active: this.activeKey } : null);
    } catch (e) {
      say(`Couldn't save your notes: ${errorMessage(e)}`, true);
    }
  }
}
