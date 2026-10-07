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

<div class="ph">
  <h2>Files</h2>
  {#if ws.files}<span class="n">{fmtN(ws.files.length)}</span>{/if}
</div>
<label class="field">
  <Icon name="filter" size={15} />
  <input bind:value={filter} placeholder="Filter files" spellcheck="false" autocomplete="off" aria-label="Filter files" />
  {#if filter}<button class="ib clear" title="Clear" onclick={() => (filter = "")}><Icon name="x" size={14} /></button>{/if}
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
      <button class="row dir" style:padding-left="{8 + r.depth * 18}px" role="treeitem" aria-expanded={r.open} aria-selected="false" onclick={() => toggle(r.key)}>
        {#each Array.from({ length: r.depth }, (_, k) => 16 + k * 18) as x (x)}<span class="gd" style:left="{x}px"></span>{/each}
        <span class="tw"><Icon name={r.open ? "chevronDown" : "chevronRight"} size={13} /></span>
        <span class="label">{r.pkg.label}</span>
        <span class="n">{fmtN(r.pkg.total)}</span>
      </button>
    {:else}
      <button
        class="row file"
        class:current={r.cls.path === current}
        class:table={r.cls.type === "arsc"}
        style:padding-left="{filter ? 8 : 8 + r.depth * 18}px"
        role="treeitem"
        aria-selected={r.cls.path === current}
        title={r.cls.type === "arsc" ? "Decode into res/values files" : r.cls.path}
        onclick={() => onopen(r.cls.path)}
      >
        {#if !filter}{#each Array.from({ length: r.depth }, (_, k) => 16 + k * 18) as x (x)}<span class="gd" style:left="{x}px"></span>{/each}<span class="tw"></span>{/if}
        <Icon name="fileCode" size={15} />
        <span class="label">{name(r.cls.path)}</span>
        {#if filter}<span class="where">{dir(r.cls.path)}</span>{/if}
        <span class="n">{r.cls.size >= 0 ? fmtSize(r.cls.size) : ""}</span>
      </button>
    {/if}
  {/each}
</div>

<style>
  .clear {
    width: 22px;
    height: 22px;
  }
  .files {
    flex: 1;
    min-height: 0;
    overflow: auto;
    padding: 0 6px 12px;
  }
  .none {
    display: flex;
    align-items: center;
    gap: 8px;
    margin: 0;
    padding: 8px 10px;
    color: var(--text-3);
  }
  .none.err {
    color: var(--bad);
  }
  .row {
    position: relative;
    display: flex;
    align-items: center;
    gap: 7px;
    width: 100%;
    height: 28px;
    padding-right: 10px;
    border-radius: 7px;
    text-align: left;
    white-space: nowrap;
    color: var(--text);
  }
  .row:hover {
    background: var(--hover);
  }
  .row.current {
    background: var(--sel);
    color: var(--text-hi);
  }
  .row.file :global(svg) {
    color: var(--text-3);
  }
  .row.table .label {
    color: var(--c-type);
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
  .dir .label {
    color: var(--text);
  }
  .label {
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .where {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    font-size: 12.5px;
    color: var(--text-3);
  }
  .n {
    margin-left: auto;
    padding-left: 8px;
    font-size: 12.5px;
    color: var(--text-3);
    font-variant-numeric: tabular-nums;
  }
</style>
