<script lang="ts" module>
  import type { ClassKind } from "$lib/engine";

  /** One letter per class kind, as in most Java IDEs; coloured per kind in CSS. */
  export const KIND_LETTER: Record<ClassKind, string> = { class: "C", interface: "I", enum: "E", annotation: "@", record: "R" };
</script>

<script lang="ts">
  // Packages and classes. Only visible rows are rendered, so a 10,000-class APK
  // scrolls fine. With a filter the tree turns into a flat list of matches.
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
    onopen: (cls: string) => void;
    oncontext: (e: MouseEvent, cls: string) => void;
  }

  let { ws, filter, current, onopen, oncontext }: Props = $props();

  const ROW = 24;
  const OVERSCAN = 20;

  let list: HTMLDivElement;
  let top = $state(0);
  let height = $state(400);
  let cursor = $state(0);
  const open = new SvelteSet<string>();

  // A small file starts with every package open. The workbench remounts this per file.
  untrack(() => {
    if (ws.classes.length > 60) return;
    const walk = (p: Pkg) =>
      p.pkgs.forEach((sub) => {
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

  const rows = $derived.by((): Row[] => {
    if (matches) return matches.map((cls) => ({ type: "cls", key: cls.id, depth: 0, cls }));
    void open.size;
    return visibleRows(ws.tree, open);
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
    else onopen(r.cls.id);
  }

  export function focusTree() {
    list?.focus();
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
          // Up to the parent package.
          for (let i = cursor - 1; i >= 0; i--) {
            if (rows[i].type === "pkg" && rows[i].depth < r.depth) {
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
  <div class="sizer" style:height="{rows.length * ROW}px">
    {#each rows.slice(first, last) as r, j (r.key)}
      {@const i = first + j}
      {#if r.type === "pkg"}
        <div
          class="row pkg"
          class:cursor={i === cursor}
          style:top="{i * ROW}px"
          style:padding-left="{8 + r.depth * 14}px"
          role="treeitem"
          aria-expanded={r.open}
          aria-selected={i === cursor}
          tabindex="-1"
          onclick={() => activate(i)}
          onkeydown={() => {}}
        >
          <span class="chev"><Icon name={r.open ? "chevronDown" : "chevronRight"} size={13} /></span>
          <span class="label">{r.pkg.label}</span>
          <span class="n">{fmtN(r.pkg.total)}</span>
        </div>
      {:else}
        {@const renamed = ws.isRenamed(r.cls.id)}
        <div
          class="row cls"
          class:cursor={i === cursor}
          class:current={r.cls.id === current}
          style:top="{i * ROW}px"
          style:padding-left="{matches ? 10 : 8 + r.depth * 14 + 17}px"
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
          <span class="kind k-{r.cls.kind}">{KIND_LETTER[r.cls.kind]}</span>
          <span class="label" class:renamed>{ws.className(r.cls.id)}</span>
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
    font-size: 12.5px;
  }
  .sizer {
    position: relative;
  }
  .row {
    position: absolute;
    left: 0;
    right: 0;
    height: 24px;
    display: flex;
    align-items: center;
    gap: 6px;
    padding-right: 10px;
    white-space: nowrap;
    cursor: pointer;
    color: var(--text);
  }
  .row:hover {
    background: rgba(255, 255, 255, 0.03);
  }
  .tree:focus .row.cursor {
    box-shadow: inset 0 0 0 1px var(--line-2);
  }
  .row.current {
    background: var(--sel);
  }
  .chev {
    display: grid;
    place-items: center;
    width: 13px;
    color: var(--faint);
  }
  .pkg .label {
    color: var(--muted);
  }
  .label {
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .label.renamed {
    color: var(--text-hi);
    font-style: italic;
  }
  .n {
    margin-left: auto;
    padding-left: 8px;
    font: 11px var(--font-code);
    color: var(--faint);
  }
  .kind {
    flex: none;
    width: 11px;
    text-align: center;
    font: 600 10.5px var(--font-code);
  }
  .k-class {
    color: var(--c-type);
  }
  .k-interface {
    color: var(--c-keyword);
  }
  .k-enum {
    color: var(--c-number);
  }
  .k-annotation {
    color: var(--c-annotation);
  }
  .k-record {
    color: var(--c-method);
  }
  .pkgname {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    font: 11px var(--font-code);
    color: var(--faint);
  }
  .none {
    margin: 0;
    padding: 14px 12px;
    color: var(--faint);
  }
</style>
