<script lang="ts">
  // Where a class, method or field is used, grouped by the class the code is in.
  import type { Usage } from "$lib/engine";
  import Icon from "$lib/Icon.svelte";
  import { fmtN } from "$lib/format";
  import Snippet from "./Snippet.svelte";
  import { dotted, type UsagesView, type Workspace } from "./workspace.svelte";

  let { ws, view, onopen, onclose }: { ws: Workspace; view: UsagesView; onopen: (u: Usage) => void; onclose: () => void } = $props();

  const groups = $derived.by(() => {
    const m = new Map<string, Usage[]>();
    for (const u of view.usages) m.set(u.cls, [...(m.get(u.cls) ?? []), u]);
    return [...m.entries()];
  });

  const kindWord = { class: "class", method: "method", field: "field" } as const;
</script>

<section class="panel" aria-label="Usages">
  <header class="ph">
    <h2>Usages of <code>{view.target.name}</code></h2>
    <span class="detail">{kindWord[view.target.kind]} {view.target.kind === "class" ? dotted(view.target.id) : view.target.detail}</span>
    <span class="acts">
      <span class="count">
        {#if view.state === "loading"}<i class="spin"></i>Looking{:else if view.state === "ready"}{fmtN(view.usages.length)} in {fmtN(groups.length)} {groups.length === 1 ? "class" : "classes"}{/if}
      </span>
      <button class="ib" title="Close (Esc)" onclick={onclose}><Icon name="x" size={15} /></button>
    </span>
  </header>
  <div class="body">
    {#if view.state === "error"}
      <p class="none err">{view.message}</p>
    {:else if view.state === "ready" && !view.usages.length}
      <p class="none">Nothing in this file uses it.</p>
    {/if}
    {#each groups as [cls, uses] (cls)}
      <div class="cls"><span class="k k-c">C</span><span class="cname">{ws.className(cls)}</span><span class="pkg">{dotted(cls.slice(0, Math.max(0, cls.lastIndexOf("/"))))}</span></div>
      {#each uses as u (u.line + ":" + u.col)}
        <button class="use" onclick={() => onopen(u)}>
          <span class="line">{u.line + 1}</span>
          <Snippet text={u.text} col={u.col} len={u.len} />
          {#if u.in}<span class="in">{u.in.kind === "method" ? u.in.detail : u.in.name}</span>{/if}
        </button>
      {/each}
    {/each}
  </div>
</section>

<style>
  .panel {
    display: flex;
    flex-direction: column;
    min-height: 0;
    height: 100%;
    background: var(--panel);
  }
  h2 code {
    font-size: 13.5px;
    font-weight: 500;
  }
  .detail {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font: 12.5px var(--font-code);
    color: var(--text-3);
  }
  .count {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 0 8px;
    color: var(--text-3);
    white-space: nowrap;
  }
  .body {
    flex: 1;
    min-height: 0;
    overflow: auto;
    padding: 0 6px 8px;
  }
  .cls {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 8px 10px 3px;
  }
  .cname {
    font-weight: 600;
    color: var(--text-hi);
  }
  .pkg {
    font-size: 12.5px;
    color: var(--text-3);
  }
  .use {
    display: flex;
    align-items: center;
    gap: 12px;
    width: 100%;
    min-height: 27px;
    padding: 3px 10px 3px 14px;
    border-radius: 7px;
    text-align: left;
  }
  .use:hover {
    background: var(--hover);
  }
  .line {
    flex: none;
    width: 4ch;
    text-align: right;
    font: 12px var(--font-code);
    color: var(--text-3);
  }
  .in {
    margin-left: auto;
    padding-left: 12px;
    flex: none;
    max-width: 40%;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font: 12px var(--font-code);
    color: var(--text-3);
  }
  .none {
    margin: 0;
    padding: 8px 10px;
    color: var(--text-3);
  }
  .err {
    color: var(--bad);
  }
</style>
