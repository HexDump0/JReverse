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
  <header>
    <h2>Usages of <span class="target">{view.target.name}</span></h2>
    <span class="detail">{kindWord[view.target.kind]} {view.target.kind === "class" ? dotted(view.target.id) : view.target.detail}</span>
    <span class="count">
      {#if view.state === "loading"}<i class="spin"></i>Looking{:else if view.state === "ready"}{fmtN(view.usages.length)} in {fmtN(groups.length)} {groups.length === 1 ? "class" : "classes"}{/if}
    </span>
    <button class="ib" title="Close (Esc)" onclick={onclose}><Icon name="x" size={14} /></button>
  </header>
  <div class="body">
    {#if view.state === "error"}
      <p class="none err">{view.message}</p>
    {:else if view.state === "ready" && !view.usages.length}
      <p class="none">Nothing in this file uses it.</p>
    {/if}
    {#each groups as [cls, uses] (cls)}
      <div class="cls"><span class="cname">{ws.className(cls)}</span><span class="pkg">{dotted(cls.slice(0, Math.max(0, cls.lastIndexOf("/"))))}</span></div>
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
    background: var(--side);
  }
  header {
    display: flex;
    align-items: center;
    gap: 12px;
    height: 34px;
    flex: none;
    padding: 0 6px 0 14px;
    border-bottom: 1px solid var(--line);
    white-space: nowrap;
  }
  h2 {
    margin: 0;
    font: 500 12.5px var(--font-ui);
    color: var(--text-2);
  }
  .target {
    color: var(--text-hi);
    font-weight: 600;
  }
  .detail {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    font: 11.5px var(--font-code);
    color: var(--text-3);
  }
  .count {
    margin-left: auto;
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 12px;
    color: var(--text-3);
  }
  .ib {
    width: 26px;
    height: 26px;
    display: grid;
    place-items: center;
    border-radius: 5px;
    color: var(--text-3);
  }
  .ib:hover {
    background: var(--lift-2);
    color: var(--text-hi);
  }
  .body {
    flex: 1;
    min-height: 0;
    overflow: auto;
    padding: 4px 0 8px;
  }
  .cls {
    display: flex;
    align-items: baseline;
    gap: 10px;
    padding: 8px 14px 3px;
    font-size: 12.5px;
  }
  .cname {
    font-weight: 600;
    color: var(--text-hi);
  }
  .pkg {
    font: 11px var(--font-code);
    color: var(--faint);
  }
  .use {
    display: flex;
    align-items: baseline;
    gap: 12px;
    width: 100%;
    padding: 3px 14px 3px 22px;
    text-align: left;
  }
  .use:hover {
    background: rgba(255, 255, 255, 0.03);
  }
  .line {
    flex: none;
    width: 4ch;
    text-align: right;
    font: 11.5px var(--font-code);
    color: var(--gutter);
  }
  .in {
    margin-left: auto;
    padding-left: 12px;
    flex: none;
    max-width: 40%;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font: 11.5px var(--font-code);
    color: var(--faint);
  }
  .none {
    margin: 0;
    padding: 14px;
    color: var(--text-3);
  }
  .err {
    color: var(--error);
  }
</style>
