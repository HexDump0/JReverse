<script lang="ts" module>
  let tickets = 0;
</script>

<script lang="ts">
  // Every string constant in the file, sorted by shape (URLs, hosts, paths, ...), each one a way
  // into the code that uses it. Read from the bytecode, so it's there without decompiling anything.
  import { onMount } from "svelte";
  import { cancelJob, errorMessage, listStrings, onEngineProgress, type StringValue } from "$lib/engine";
  import Icon from "$lib/Icon.svelte";
  import { fmtN } from "$lib/format";
  import { libraryOf } from "./android";
  import { originalMember, ownerOf } from "./ids";
  import { javaEscape, KIND_LABEL, kindOf, type StringKind } from "./strings";
  import type { Workspace } from "./workspace.svelte";

  interface Props {
    ws: Workspace;
    /** Opens the member that uses the string, with the caret on the literal. */
    onopen: (id: string, value: string) => void;
    oncopy: (text: string, what: string) => void;
    /** Searches the decompiled code for every place, past the ones listed here. */
    onsearch: (value: string) => void;
  }

  let { ws, onopen, oncopy, onsearch }: Props = $props();

  const PAGE = 300;

  let running = $state<{ ticket: string; done: number; total: number } | null>(null);
  let error = $state("");
  let filter = $state("");
  let kind = $state<StringKind | "all">("all");
  let withLibraries = $state(false);
  let shown = $state(PAGE);
  let open = $state<string | null>(null);

  onMount(() => {
    const off = onEngineProgress((p) => {
      if (running && p.ticket === running.ticket) running = { ...running, done: p.done, total: p.total };
    });
    if (!ws.strings) load();
    return () => {
      void off.then((f) => f());
      // Leaving the panel stops the read; it starts again next time.
      if (running) cancelJob(running.ticket).catch(() => {});
    };
  });

  async function load() {
    const ticket = `strings-${++tickets}`;
    running = { ticket, done: 0, total: 0 };
    error = "";
    try {
      ws.strings = (await listStrings(ws.session, ticket)).strings;
    } catch (e) {
      if ((e as { code?: string }).code !== "CANCELLED") error = errorMessage(e);
    } finally {
      if (running?.ticket === ticket) running = null;
    }
  }

  interface Row extends StringValue {
    kind: StringKind | null;
    library: boolean;
  }

  // Bundled libraries bring thousands of strings of their own; a string counts as theirs only when
  // every place that uses it is in one.
  const rows = $derived(
    (ws.strings ?? []).map((s): Row => ({ ...s, kind: kindOf(s.value), library: s.at.every((id) => libraryOf(ownerOf(id)) !== null) })),
  );
  const own = $derived(withLibraries ? rows : rows.filter((r) => !r.library));
  const counts = $derived.by(() => {
    const c = new Map<StringKind, number>();
    for (const r of own) if (r.kind) c.set(r.kind, (c.get(r.kind) ?? 0) + 1);
    return c;
  });
  const visible = $derived.by(() => {
    const q = filter.trim().toLowerCase();
    return own.filter((r) => (kind === "all" || r.kind === kind) && (!q || r.value.toLowerCase().includes(q)));
  });
  const hiddenLibrary = $derived(rows.length - rows.filter((r) => !r.library).length);

  $effect(() => {
    // A new filter starts from the top again.
    void filter;
    void kind;
    void withLibraries;
    shown = PAGE;
  });

  function place(id: string): string {
    const owner = ownerOf(id);
    const cls = ws.className(owner);
    const member = originalMember(id);
    if (member === "<init>") return `new ${cls}`;
    if (member === "<clinit>") return `${cls} static init`;
    return `${cls}.${ws.project.renames[id] ?? member}`;
  }

  function choose(r: Row) {
    if (r.at.length === 1) onopen(r.at[0], r.value);
    else open = open === r.value ? null : r.value;
  }
</script>

<div class="ph">
  <h2>Strings</h2>
  {#if ws.strings}<span class="n">{fmtN(own.length)}</span>{/if}
</div>
<label class="field">
  <Icon name="filter" size={15} />
  <input bind:value={filter} placeholder="Filter strings" spellcheck="false" autocomplete="off" aria-label="Filter strings" />
  {#if filter}<button class="ib clear" title="Clear" onclick={() => (filter = "")}><Icon name="x" size={14} /></button>{/if}
</label>
{#if ws.strings}
  <div class="kinds" role="radiogroup" aria-label="Show">
    <button role="radio" aria-checked={kind === "all"} class:on={kind === "all"} onclick={() => (kind = "all")}>All</button>
    {#each Object.keys(KIND_LABEL) as k (k)}
      {@const n = counts.get(k as StringKind) ?? 0}
      {#if n}
        <button role="radio" aria-checked={kind === k} class:on={kind === k} onclick={() => (kind = k as StringKind)}>{KIND_LABEL[k as StringKind]}<span class="cn">{fmtN(n)}</span></button>
      {/if}
    {/each}
  </div>
{/if}

{#if running}
  <div class="prog">
    <span>{running.total ? `Reading ${fmtN(running.done)} of ${fmtN(running.total)} members` : "Reading the code"}</span>
    {#if running.total}<div class="bar"><i style:width="{(running.done / running.total) * 100}%"></i></div>{/if}
  </div>
{:else if error}
  <p class="msg err">{error} <button class="lnk" onclick={load}>Try again</button></p>
{:else if ws.strings}
  {#if hiddenLibrary}
    <p class="msg">
      <button class="lnk" onclick={() => (withLibraries = !withLibraries)}>{withLibraries ? "Hide" : "Show"} {fmtN(hiddenLibrary)} from libraries</button>
    </p>
  {/if}
  <div class="list selectable">
    {#if !visible.length}
      <p class="msg dim">{rows.length ? "No string matches." : "This file has no string constants."}</p>
    {/if}
    {#each visible.slice(0, shown) as r (r.value)}
      <div class="row" class:open={open === r.value}>
        <button class="str" onclick={() => choose(r)} title={r.value.length > 600 ? `${r.value.slice(0, 600)}...` : r.value}>
          <span class="v">{javaEscape(r.value)}</span>
          {#if r.uses > 1}<span class="cn">{fmtN(r.uses)}</span>{/if}
        </button>
        <button class="ib cp" title="Copy" onclick={() => oncopy(r.value, "the string")}><Icon name="copy" size={14} /></button>
      </div>
      {#if open === r.value}
        <div class="places">
          {#each r.at as id (id)}
            <button class="pl" onclick={() => onopen(id, r.value)}><span class="k {id.includes('(') ? 'k-m' : 'k-f'}">{id.includes("(") ? "m" : "f"}</span>{place(id)}</button>
          {/each}
          {#if r.uses > r.at.length}<button class="lnk more" onclick={() => onsearch(r.value)}>Search for all {fmtN(r.uses)}</button>{/if}
        </div>
      {/if}
    {/each}
    {#if visible.length > shown}
      <button class="lnk page" onclick={() => (shown += PAGE)}>Show {fmtN(Math.min(PAGE, visible.length - shown))} more of {fmtN(visible.length - shown)}</button>
    {/if}
  </div>
{/if}

<style>
  .kinds {
    display: flex;
    flex-wrap: wrap;
    gap: 2px;
    padding: 0 10px 10px;
    flex: none;
  }
  .kinds button {
    display: flex;
    align-items: baseline;
    gap: 6px;
    padding: 3px 8px;
    border-radius: 7px;
    font-size: 13px;
    color: var(--text-3);
  }
  .kinds button:hover {
    color: var(--text);
    background: var(--hover);
  }
  .kinds button.on {
    background: var(--sel);
    color: var(--text-hi);
  }
  .cn {
    font-size: 12px;
    color: var(--text-3);
    font-variant-numeric: tabular-nums;
  }
  .prog {
    padding: 10px 16px;
    border-top: 1px solid var(--line);
    color: var(--text-2);
  }
  .bar {
    height: 3px;
    margin-top: 8px;
    border-radius: 2px;
    background: var(--line);
    overflow: hidden;
  }
  .bar i {
    display: block;
    height: 100%;
    background: var(--accent);
  }
  .msg {
    margin: 0;
    padding: 8px 16px;
    border-top: 1px solid var(--line);
    color: var(--text-2);
  }
  .dim {
    color: var(--text-3);
    border-top: 0;
  }
  .err {
    color: var(--bad);
  }
  .lnk {
    color: var(--text-3);
    text-decoration: underline;
    text-underline-offset: 3px;
  }
  .lnk:hover {
    color: var(--text-hi);
  }
  .list {
    flex: 1;
    min-height: 0;
    overflow: auto;
    padding: 2px 6px 12px;
  }
  .row {
    display: flex;
    align-items: center;
    border-radius: 7px;
  }
  .row:hover,
  .row.open {
    background: var(--hover);
  }
  .str {
    flex: 1;
    min-width: 0;
    display: flex;
    align-items: baseline;
    gap: 8px;
    padding: 5px 4px 5px 10px;
    text-align: left;
  }
  .v {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font: 12.5px var(--font-code);
    color: var(--text-hi);
  }
  .str .cn {
    margin-left: auto;
    flex: none;
  }
  .cp {
    width: 24px;
    height: 24px;
    margin-right: 4px;
    visibility: hidden;
  }
  .row:hover .cp {
    visibility: visible;
  }
  .places {
    padding: 2px 0 6px 18px;
  }
  .pl {
    display: flex;
    align-items: center;
    gap: 8px;
    width: 100%;
    padding: 4px 10px;
    border-radius: 7px;
    text-align: left;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    color: var(--text-2);
  }
  .pl:hover {
    background: var(--hover);
    color: var(--text-hi);
  }
  .more {
    margin: 2px 0 0;
    padding: 2px 10px;
    font-size: 12.5px;
    color: var(--text-3);
  }
  .page {
    display: block;
    margin: 8px 10px;
  }
</style>
