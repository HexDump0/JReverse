<script lang="ts">
  // Everything in the archive that isn't code: resources, assets, configs, native libraries.
  import { SvelteSet } from "svelte/reactivity";
  import type { FileEntry } from "$lib/engine";
  import Icon from "$lib/Icon.svelte";
  import { fmtN, fmtSize } from "$lib/format";
  import { buildTree, visibleRows, type Row } from "./tree";
  import type { Workspace } from "./workspace.svelte";

  let { ws, current, onopen }: { ws: Workspace; current: string | undefined; onopen: (path: string) => void } = $props();

  type Item = FileEntry & { id: string };

  let filter = $state("");
  const open = new SvelteSet<string>();

  $effect(() => {
    ws.loadFiles();
  });

  const items = $derived<Item[]>((ws.files ?? []).map((f) => ({ ...f, id: f.path })));
  const tree = $derived(buildTree(items, "/"));

  const rows = $derived.by((): Row<Item>[] => {
    const q = filter.trim().toLowerCase();
    if (q) return items.filter((f) => f.path.toLowerCase().includes(q)).map((cls) => ({ type: "cls", key: cls.id, depth: 0, cls }));
    void open.size;
    return visibleRows(tree, open);
  });

  // resources.arsc's files land under res/; open it so they show.
  let had = 0;
  $effect(() => {
    const n = items.length;
    if (had && n > had) open.add("res");
    had = n;
  });

  const name = (path: string) => path.slice(path.lastIndexOf("/") + 1);
  const dir = (path: string) => path.slice(0, Math.max(0, path.lastIndexOf("/")));

  function toggle(path: string) {
    if (open.has(path)) open.delete(path);
    else open.add(path);
  }
</script>

<label class="filter">
  <Icon name="search" size={14} />
  <input bind:value={filter} placeholder={ws.files ? `Filter ${fmtN(ws.files.length)} files` : "Filter files"} spellcheck="false" autocomplete="off" aria-label="Filter files" />
  {#if filter}<button class="clear" title="Clear" onclick={() => (filter = "")}><Icon name="x" size={13} /></button>{/if}
</label>

<div class="files" role="tree" aria-label="Files">
  {#if ws.filesError}
    <p class="none err">{ws.filesError}</p>
  {:else if !ws.files}
    <p class="none"><i class="spin"></i>Listing files</p>
  {:else if !ws.files.length}
    <p class="none">Nothing but code in this file.</p>
  {/if}
  {#each rows as r (r.key)}
    {#if r.type === "pkg"}
      <button class="row dir" style:padding-left="{8 + r.depth * 14}px" role="treeitem" aria-expanded={r.open} aria-selected="false" onclick={() => toggle(r.key)}>
        <span class="chev"><Icon name={r.open ? "chevronDown" : "chevronRight"} size={13} /></span>
        <span class="label">{r.pkg.label}</span>
        <span class="n">{fmtN(r.pkg.total)}</span>
      </button>
    {:else}
      <button
        class="row file"
        class:current={r.cls.path === current}
        class:table={r.cls.type === "arsc"}
        style:padding-left="{filter ? 10 : 8 + r.depth * 14 + 19}px"
        role="treeitem"
        aria-selected={r.cls.path === current}
        title={r.cls.type === "arsc" ? "Decode into res/values files" : r.cls.path}
        onclick={() => onopen(r.cls.path)}
      >
        <span class="label">{name(r.cls.path)}</span>
        {#if filter}<span class="where">{dir(r.cls.path)}</span>{/if}
        <span class="n">{r.cls.size >= 0 ? fmtSize(r.cls.size) : ""}</span>
      </button>
    {/if}
  {/each}
</div>

<style>
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
  .files {
    flex: 1;
    min-height: 0;
    overflow: auto;
    padding-bottom: 12px;
    font-size: 12.5px;
  }
  .row {
    display: flex;
    align-items: center;
    gap: 6px;
    width: 100%;
    height: 24px;
    padding-right: 10px;
    text-align: left;
    white-space: nowrap;
    color: var(--text);
  }
  .row:hover {
    background: rgba(255, 255, 255, 0.03);
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
  .dir .label {
    color: var(--muted);
  }
  .table .label {
    text-decoration: underline;
    text-decoration-color: var(--line-2);
    text-underline-offset: 3px;
  }
  .label {
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .where {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    font: 11px var(--font-code);
    color: var(--faint);
  }
  .n {
    margin-left: auto;
    padding-left: 8px;
    font: 11px var(--font-code);
    color: var(--faint);
  }
  .none {
    display: flex;
    align-items: center;
    gap: 8px;
    margin: 0;
    padding: 14px 12px;
    color: var(--faint);
  }
  .err {
    color: var(--error);
  }
</style>
