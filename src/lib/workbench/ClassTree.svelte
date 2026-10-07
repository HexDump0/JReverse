<script lang="ts" module>
  import type { ClassKind } from "$lib/engine";

  /** One letter per class kind, as in most Java IDEs; coloured per kind in app.css. */
  export const KIND_LETTER: Record<ClassKind, string> = { class: "C", interface: "I", enum: "E", annotation: "@", record: "R" };
  export const KIND_CLASS: Record<ClassKind, string> = { class: "k-c", interface: "k-i", enum: "k-e", annotation: "k-a", record: "k-r" };

  /** A member of the class in the active tab, listed under it. */
  export interface Member {
    id: string;
    kind: "method" | "field" | "class";
    name: string;
    /** Method parameters, `(int, String)`. */
    params: string;
    line: number;
    col: number;
  }
</script>

<script lang="ts">
  // Packages and classes, and the members of the open class under it. Only
  // visible rows are rendered, so a 10,000-class APK scrolls fine. With a
  // filter the tree turns into a flat list of matches.
  import { tick, untrack } from "svelte";
  import { SvelteSet } from "svelte/reactivity";
  import type { ClassEntry } from "$lib/engine";
  import Icon from "$lib/Icon.svelte";
  import { fmtN } from "$lib/format";
  import { pathsTo, visibleRows, type Pkg, type Row } from "./tree";
  import { dotted, type Workspace } from "./workspace.svelte";

  interface Props {
    ws: Workspace;
    filter: string;
    /** The class in the active tab. */
    current: string | undefined;
    members?: Member[];
    /** The member the caret is in. */
    memberAt?: string;
    onopen: (cls: string) => void;
    onmember: (m: Member) => void;
    oncontext: (e: MouseEvent, cls: string) => void;
  }

  let { ws, filter, current, members = [], memberAt, onopen, onmember, oncontext }: Props = $props();

  type TreeRow = Row | { type: "mem"; key: string; depth: number; mem: Member };

  const ROW = 28;
  const STEP = 18;
  const OVERSCAN = 20;

  let list: HTMLDivElement;
  let top = $state(0);
  let height = $state(400);
  let cursor = $state(0);
  let membersShut = $state(false);
  const open = new SvelteSet<string>();

  // A small app starts with its own packages open (never the libraries). The workbench remounts this per file.
  untrack(() => {
    const own = ws.tree.total - (ws.tree.pkgs.find((p) => p.lib)?.total ?? 0);
    if (own > 80) return;
    const walk = (p: Pkg) =>
      p.pkgs.forEach((sub) => {
        if (sub.group) return;
        open.add(sub.path);
        walk(sub);
      });
    walk(ws.tree);
  });

  const matches = $derived.by((): ClassEntry[] | null => {
    const q = filter.trim().toLowerCase().replaceAll(".", "/");
    if (!q) return null;
    void ws.names;
    const scored: [number, ClassEntry][] = [];
    for (const c of ws.classes) {
      const name = ws.className(c.id).toLowerCase();
      const id = c.id.toLowerCase();
      const score = name === q ? 0 : name.startsWith(q) ? 1 : name.includes(q) ? 2 : id.includes(q) ? 3 : -1;
      if (score >= 0) scored.push([score, c]);
    }
    scored.sort((a, b) => a[0] - b[0] || a[1].id.localeCompare(b[1].id));
    return scored.map(([, c]) => c);
  });

  const rows = $derived.by((): TreeRow[] => {
    if (matches) return matches.map((cls) => ({ type: "cls", key: cls.id, depth: 0, cls }));
    void open.size;
    const base: TreeRow[] = visibleRows(ws.tree, open);
    if (!current || !members.length || membersShut) return base;
    const at = base.findIndex((r) => r.key === current);
    if (at < 0) return base;
    const depth = base[at].depth + 1;
    const mems: TreeRow[] = members.map((m) => ({ type: "mem", key: `mem:${m.id}`, depth, mem: m }));
    return [...base.slice(0, at + 1), ...mems, ...base.slice(at + 1)];
  });

  const first = $derived(Math.max(0, Math.floor(top / ROW) - OVERSCAN));
  const last = $derived(Math.min(rows.length, Math.ceil((top + height) / ROW) + OVERSCAN));

  $effect(() => {
    void filter;
    cursor = 0;
    if (list) list.scrollTop = 0;
  });

  // Follow the active tab: open its package and bring it into view.
  $effect(() => {
    const cls = current;
    if (!cls || matches) return;
    untrack(() => (membersShut = false));
    for (const p of pathsTo(ws.tree, cls)) open.add(p);
    tick().then(() => {
      const i = rows.findIndex((r) => r.key === cls);
      if (i >= 0) {
        cursor = i;
        scrollTo(i);
      }
    });
  });

  $effect(() => {
    const ro = new ResizeObserver(() => (height = list.clientHeight));
    ro.observe(list);
    return () => ro.disconnect();
  });

  function scrollTo(i: number) {
    const y = i * ROW;
    if (y < list.scrollTop) list.scrollTop = y;
    else if (y + ROW > list.scrollTop + list.clientHeight) list.scrollTop = y + ROW - list.clientHeight;
  }

  function toggle(path: string) {
    if (open.has(path)) open.delete(path);
    else open.add(path);
  }

  function activate(i: number) {
    const r = rows[i];
    if (!r) return;
    cursor = i;
    if (r.type === "pkg") toggle(r.key);
    else if (r.type === "mem") onmember(r.mem);
    else if (r.cls.id === current && members.length) membersShut = !membersShut;
    else onopen(r.cls.id);
  }

  export function focusTree() {
    list?.focus();
  }

  /** Scrolls the open class into view, e.g. from the panel header. */
  export function revealCurrent() {
    const i = rows.findIndex((r) => r.key === current);
    if (i >= 0) {
      cursor = i;
      scrollTo(i);
    }
  }

  export function collapseAll() {
    open.clear();
    list.scrollTop = 0;
    cursor = 0;
  }

  function onkeydown(e: KeyboardEvent) {
    const r = rows[cursor];
    switch (e.key) {
      case "ArrowDown":
        cursor = Math.min(rows.length - 1, cursor + 1);
        break;
      case "ArrowUp":
        cursor = Math.max(0, cursor - 1);
        break;
      case "PageDown":
        cursor = Math.min(rows.length - 1, cursor + Math.floor(list.clientHeight / ROW));
        break;
      case "PageUp":
        cursor = Math.max(0, cursor - Math.floor(list.clientHeight / ROW));
        break;
      case "Home":
        cursor = 0;
        break;
      case "End":
        cursor = rows.length - 1;
        break;
      case "ArrowRight":
        if (r?.type === "pkg" && !r.open) open.add(r.key);
        else if (r?.type === "pkg") cursor = Math.min(rows.length - 1, cursor + 1);
        break;
      case "ArrowLeft":
        if (r?.type === "pkg" && r.open) open.delete(r.key);
        else if (r) {
          // Up to the parent.
          for (let i = cursor - 1; i >= 0; i--) {
            if (rows[i].type !== "mem" && rows[i].depth < r.depth) {
              cursor = i;
              break;
            }
          }
        }
        break;
      case "Enter":
        activate(cursor);
        break;
      default:
        return;
    }
    e.preventDefault();
    scrollTo(cursor);
  }

  const pad = (depth: number) => 8 + depth * STEP;
  /** Indent guides, one per level above the row, centred under that level's twisty. */
  const guides = (depth: number) => Array.from({ length: depth }, (_, k) => pad(k) + 8);
  const memberKind = (m: Member) => (m.kind === "method" ? "m" : m.kind === "field" ? "f" : "c");
</script>

<!-- svelte-ignore a11y_no_noninteractive_tabindex -->
<div
  class="tree"
  role="tree"
  aria-label="Classes"
  tabindex="0"
  bind:this={list}
  onscroll={() => (top = list.scrollTop)}
  {onkeydown}
>
  <div class="sizer" style:height="{rows.length * ROW + 12}px">
    {#each rows.slice(first, last) as r, j (r.key)}
      {@const i = first + j}
      {#if r.type === "pkg"}
        <div
          class="row pkg"
          class:cursor={i === cursor}
          class:lib={r.pkg.group}
          style:top="{i * ROW}px"
          style:padding-left="{pad(r.depth)}px"
          role="treeitem"
          aria-expanded={r.open}
          aria-selected={i === cursor}
          tabindex="-1"
          onclick={() => activate(i)}
          onkeydown={() => {}}
        >
          {#each guides(r.depth) as x (x)}<span class="gd" style:left="{x}px"></span>{/each}
          <span class="tw"><Icon name={r.open ? "chevronDown" : "chevronRight"} size={13} /></span>
          <span class="label">{r.pkg.label}</span>
          {#if r.pkg.obf}<span class="obf" title="Most names in this package are obfuscated"><i></i>obfuscated</span>{/if}
          <span class="n">{fmtN(r.pkg.total)}</span>
        </div>
      {:else if r.type === "mem"}
        <div
          class="row mem"
          class:cursor={i === cursor}
          class:current={r.mem.id === memberAt}
          style:top="{i * ROW}px"
          style:padding-left="{pad(r.depth)}px"
          role="treeitem"
          aria-selected={r.mem.id === memberAt}
          tabindex="-1"
          onclick={() => activate(i)}
          onkeydown={() => {}}
        >
          {#each guides(r.depth) as x (x)}<span class="gd" style:left="{x}px"></span>{/each}
          <span class="tw"></span>
          <span class="k k-{memberKind(r.mem)}">{r.mem.kind === "class" ? "C" : memberKind(r.mem)}</span>
          <span class="label">{r.mem.name}<span class="dim">{r.mem.params}</span></span>
          {#if ws.project.renames[r.mem.id]}<span class="was" title="You renamed it">renamed</span>{/if}
        </div>
      {:else}
        {@const renamed = ws.isRenamed(r.cls.id)}
        {@const hasMembers = !matches && r.cls.id === current && members.length > 0}
        <div
          class="row cls"
          class:cursor={i === cursor}
          class:current={r.cls.id === current && !members.some((m) => m.id === memberAt)}
          style:top="{i * ROW}px"
          style:padding-left="{matches ? 8 : pad(r.depth)}px"
          role="treeitem"
          aria-selected={r.cls.id === current}
          tabindex="-1"
          title={dotted(r.cls.id)}
          onclick={() => activate(i)}
          onkeydown={() => {}}
          oncontextmenu={(e) => {
            e.preventDefault();
            cursor = i;
            oncontext(e, r.cls.id);
          }}
        >
          {#if !matches}
            {#each guides(r.depth) as x (x)}<span class="gd" style:left="{x}px"></span>{/each}
            <span class="tw">{#if hasMembers}<Icon name={membersShut ? "chevronRight" : "chevronDown"} size={13} />{/if}</span>
          {/if}
          <span class="k {KIND_CLASS[r.cls.kind]}">{KIND_LETTER[r.cls.kind]}</span>
          <span class="label" class:renamed>{ws.className(r.cls.id)}</span>
          {#if renamed}<span class="was">was {r.cls.id.slice(r.cls.id.lastIndexOf("/") + 1)}</span>{/if}
          {#if matches}
            <span class="pkgname">{dotted(r.cls.id.slice(0, Math.max(0, r.cls.id.lastIndexOf("/"))))}</span>
          {/if}
        </div>
      {/if}
    {/each}
  </div>
  {#if matches && !matches.length}
    <p class="none">No class matches {filter}</p>
  {/if}
</div>

<style>
  .tree {
    flex: 1;
    min-height: 0;
    overflow: auto;
    position: relative;
    outline: none;
    padding: 0 6px;
  }
  .sizer {
    position: relative;
  }
  .row {
    position: absolute;
    left: 0;
    right: 0;
    height: 28px;
    display: flex;
    align-items: center;
    gap: 7px;
    padding-right: 10px;
    border-radius: 7px;
    white-space: nowrap;
    cursor: pointer;
    color: var(--text);
  }
  .row:hover {
    background: var(--hover);
  }
  .tree:focus-visible .row.cursor {
    box-shadow: inset 0 0 0 1px var(--edge);
  }
  .row.current {
    background: var(--sel);
    color: var(--text-hi);
  }
  .gd {
    position: absolute;
    top: 0;
    bottom: 0;
    width: 1px;
    background: var(--line);
  }
  .tw {
    width: 16px;
    height: 16px;
    flex: none;
    display: grid;
    place-items: center;
    margin-right: -3px;
    color: var(--text-3);
  }
  .label {
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .lib .label {
    color: var(--text-2);
  }
  .label.renamed {
    color: var(--text-hi);
  }
  .dim {
    color: var(--text-3);
  }
  .was {
    flex: none;
    font: 12px var(--font-code);
    color: var(--text-3);
  }
  .obf {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 12.5px;
    color: var(--text-3);
  }
  .obf i {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: var(--m-obf);
  }
  .n {
    margin-left: auto;
    padding-left: 8px;
    font-size: 12.5px;
    color: var(--text-3);
    font-variant-numeric: tabular-nums;
  }
  .pkgname {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    font-size: 12.5px;
    color: var(--text-3);
  }
  .none {
    margin: 0;
    padding: 14px 10px;
    color: var(--text-3);
  }
</style>
