<script lang="ts" module>
  // Module-wide, so a remounted panel never reuses the ticket of a search still running.
  let tickets = 0;
</script>

<script lang="ts">
  // Search across the whole file: class and member names, decompiled code, string literals, files.
  import { tick } from "svelte";
  import { cancelJob, errorMessage, isFileHit, isNameHit, onEngineProgress, search, type CodeHit, type FileHit, type SearchHit, type SearchResult, type SearchScope } from "$lib/engine";
  import Icon from "$lib/Icon.svelte";
  import { fmtN } from "$lib/format";
  import Snippet from "./Snippet.svelte";
  import { dotted, type Workspace } from "./workspace.svelte";

  let { ws, onopen }: { ws: Workspace; onopen: (hit: SearchHit) => void } = $props();

  const SCOPES: { id: SearchScope; label: string }[] = [
    { id: "classes", label: "Classes" },
    { id: "members", label: "Members" },
    { id: "code", label: "Code" },
    { id: "strings", label: "Strings" },
    { id: "files", label: "Files" },
  ];

  let input = $state<HTMLInputElement>();
  let query = $state("");
  let regex = $state(false);
  let caseSensitive = $state(false);
  let scopes = $state<Record<SearchScope, boolean>>({ classes: true, members: true, code: false, strings: true, files: true });
  let running = $state<{ ticket: string; done: number; total: number } | null>(null);
  let result = $state<SearchResult | null>(null);
  let error = $state("");
  let searched = $state("");

  export async function focusSearch(text?: string) {
    if (text) query = text;
    await tick();
    input?.select();
  }

  $effect(() => {
    const off = onEngineProgress((p) => {
      if (running && p.ticket === running.ticket) running = { ...running, done: p.done, total: p.total };
    });
    return () => {
      void off.then((f) => f());
      // Leaving the panel ends its search; nothing would show the result.
      if (running) cancelJob(running.ticket).catch(() => {});
    };
  });

  async function run() {
    const q = query.trim();
    const chosen = SCOPES.filter((s) => scopes[s.id]).map((s) => s.id);
    if (!q || !chosen.length) return;
    if (running) await cancelJob(running.ticket).catch(() => {});
    const ticket = `search-${++tickets}`;
    running = { ticket, done: 0, total: 0 };
    error = "";
    try {
      const r = await search(ws.session, { query: q, regex, caseSensitive, scopes: chosen, ticket });
      if (running?.ticket !== ticket) return;
      result = r;
      searched = q;
    } catch (e) {
      if (running?.ticket !== ticket) return;
      const err = e as { code?: string };
      if (err.code !== "CANCELLED") error = errorMessage(e);
    } finally {
      if (running?.ticket === ticket) running = null;
    }
  }

  function cancel() {
    if (running) cancelJob(running.ticket).catch(() => {});
  }

  const names = $derived((result?.hits ?? []).filter(isNameHit));
  const code = $derived.by(() => {
    const m = new Map<string, CodeHit[]>();
    for (const h of result?.hits ?? []) if (!isNameHit(h) && !isFileHit(h)) m.set(h.cls, [...(m.get(h.cls) ?? []), h]);
    return [...m.entries()];
  });
  const inFiles = $derived.by(() => {
    const m = new Map<string, FileHit[]>();
    for (const h of result?.hits ?? []) if (isFileHit(h)) m.set(h.path, [...(m.get(h.path) ?? []), h]);
    return [...m.entries()];
  });
  const fileCount = $derived(inFiles.reduce((k, [, hits]) => k + hits.length, 0));
  const codeCount = $derived(code.reduce((k, [, hits]) => k + hits.length, 0));

  function onkeydown(e: KeyboardEvent) {
    if (e.key === "Enter") {
      e.preventDefault();
      run();
    } else if (e.key === "Escape" && running) {
      e.preventDefault();
      e.stopPropagation();
      cancel();
    }
  }
</script>

<div class="search">
  <div class="box">
    <label class="q">
      <Icon name="search" size={14} />
      <input
        bind:this={input}
        bind:value={query}
        {onkeydown}
        placeholder="Search, then Enter"
        spellcheck="false"
        autocomplete="off"
        aria-label="Search the file"
      />
    </label>
    <div class="opts">
      {#each SCOPES as s (s.id)}
        <button class="tog" class:on={scopes[s.id]} aria-pressed={scopes[s.id]} onclick={() => (scopes[s.id] = !scopes[s.id])}>{s.label}</button>
      {/each}
    </div>
    <div class="opts">
      <button class="tog" class:on={caseSensitive} aria-pressed={caseSensitive} onclick={() => (caseSensitive = !caseSensitive)}>Match case</button>
      <button class="tog" class:on={regex} aria-pressed={regex} onclick={() => (regex = !regex)}>Regex</button>
    </div>
  </div>

  {#if running}
    <div class="prog">
      <div class="pt">
        <span>{running.total ? `Decompiling ${fmtN(running.done)} of ${fmtN(running.total)} classes` : "Searching"}</span>
        <button class="lnk" onclick={cancel}>Cancel</button>
      </div>
      {#if running.total}<div class="bar"><i style:width="{(running.done / running.total) * 100}%"></i></div>{/if}
    </div>
  {:else if error}
    <p class="msg err">{error}</p>
  {:else if result}
    <p class="msg">
      {#if !result.hits.length}Nothing matches {searched}{:else}{fmtN(result.hits.length)}{result.truncated ? "+" : ""} results{#if result.searched}{" "}<span class="dim">in {fmtN(result.searched)} classes, {result.ms < 1000 ? `${result.ms} ms` : `${(result.ms / 1000).toFixed(1)} s`}</span>{/if}{/if}
    </p>
  {:else}
    <p class="msg dim">Code and Strings decompile every class on the first search.</p>
  {/if}

  <div class="results">
    {#if names.length}
      <div class="sec">Names <span class="n">{fmtN(names.length)}</span></div>
      {#each names as h, i (i)}
        <button class="hit name" onclick={() => onopen(h)} title={h.node.kind === "class" ? dotted(h.node.id) : h.node.detail}>
          <span class="kind">{h.type === "class" ? "C" : h.type === "method" ? "m" : "f"}</span>
          <span class="nm">{h.node.name}</span>
          <span class="where">{h.type === "class" ? dotted(h.node.id.slice(0, Math.max(0, h.node.id.lastIndexOf("/")))) : ws.className(h.cls)}</span>
        </button>
      {/each}
    {/if}
    {#if code.length}
      <div class="sec">Code <span class="n">{fmtN(codeCount)}</span></div>
      {#each code as [cls, hits] (cls)}
        <div class="cls" title={dotted(cls)}>{ws.className(cls)}</div>
        {#each hits as h (h.line)}
          <button class="hit" onclick={() => onopen(h)}>
            <span class="line">{h.line + 1}</span>
            <Snippet text={h.text} col={h.col} len={h.len} />
          </button>
        {/each}
      {/each}
    {/if}
    {#if inFiles.length}
      <div class="sec">Files <span class="n">{fmtN(fileCount)}</span></div>
      {#each inFiles as [path, hits] (path)}
        <div class="cls" title={path}>{path.slice(path.lastIndexOf("/") + 1)}<span class="dir">{path.includes("/") ? path.slice(0, path.lastIndexOf("/")) : ""}</span></div>
        {#each hits as h (h.line)}
          <button class="hit" onclick={() => onopen(h)}>
            <span class="line">{h.line + 1}</span>
            <Snippet text={h.text} col={h.col} len={h.len} />
          </button>
        {/each}
      {/each}
    {/if}
  </div>
</div>

<style>
  .search {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
  }
  .box {
    padding: 8px 10px 10px;
    border-bottom: 1px solid var(--line);
  }
  .q {
    display: flex;
    align-items: center;
    gap: 8px;
    height: 30px;
    padding: 0 9px;
    border-radius: 6px;
    background: var(--pane);
    color: var(--text-3);
    box-shadow: inset 0 0 0 1px var(--line-2);
  }
  .q:focus-within {
    box-shadow: inset 0 0 0 1px var(--beam-shade);
  }
  .q input {
    flex: 1;
    min-width: 0;
    border: 0;
    outline: none;
    background: transparent;
    font-size: 12.5px;
    color: var(--text-hi);
  }
  .q input::placeholder {
    color: var(--text-3);
  }
  .opts {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
    margin-top: 8px;
  }
  .tog {
    height: 22px;
    padding: 0 8px;
    border-radius: 4px;
    font-size: 11.5px;
    color: var(--text-3);
    box-shadow: inset 0 0 0 1px var(--line-2);
  }
  .tog:hover {
    color: var(--text);
  }
  .tog.on {
    color: var(--text-hi);
    background: var(--lift-2);
    box-shadow: none;
  }
  .prog {
    padding: 10px 12px;
    font-size: 12px;
    color: var(--text-2);
  }
  .pt {
    display: flex;
    justify-content: space-between;
    gap: 8px;
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
  .lnk {
    color: var(--text-3);
    text-decoration: underline;
    text-underline-offset: 3px;
  }
  .lnk:hover {
    color: var(--text-hi);
  }
  .msg {
    margin: 0;
    padding: 10px 12px;
    font-size: 12px;
    color: var(--text-2);
  }
  .dim {
    color: var(--text-3);
  }
  .err {
    color: var(--error);
  }
  .results {
    flex: 1;
    min-height: 0;
    overflow: auto;
    padding-bottom: 12px;
  }
  .sec {
    padding: 10px 12px 4px;
    font-size: 11.5px;
    font-weight: 600;
    color: var(--text-3);
  }
  .sec .n {
    font: 400 11px var(--font-code);
  }
  .cls {
    padding: 8px 12px 2px;
    font-size: 12.5px;
    font-weight: 600;
    color: var(--text-hi);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .dir {
    margin-left: 8px;
    font: 400 11px var(--font-code);
    color: var(--faint);
  }
  .hit {
    display: flex;
    align-items: baseline;
    gap: 8px;
    width: 100%;
    padding: 3px 12px;
    text-align: left;
    white-space: nowrap;
  }
  .hit:hover {
    background: rgba(255, 255, 255, 0.03);
  }
  .line {
    flex: none;
    width: 4ch;
    text-align: right;
    font: 11px var(--font-code);
    color: var(--gutter);
  }
  .name .kind {
    flex: none;
    width: 11px;
    font: 600 10.5px var(--font-code);
    color: var(--c-type);
  }
  .nm {
    font-size: 12.5px;
    color: var(--text);
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .where {
    min-width: 0;
    margin-left: auto;
    overflow: hidden;
    text-overflow: ellipsis;
    font: 11px var(--font-code);
    color: var(--faint);
  }
</style>
