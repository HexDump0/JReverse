<script lang="ts" module>
  export interface PaletteItem {
    section: string;
    label: string;
    sub?: string;
    /** Shown alone when the query starts with `>`. */
    action?: boolean;
    run: () => void;
  }
</script>

<script lang="ts">
  import { tick } from "svelte";

  const MAX_SHOWN = 200;

  let {
    items,
    placeholder,
    onclose,
  }: { items: PaletteItem[]; placeholder: string; onclose: () => void } = $props();

  let query = $state("");
  let sel = $state(0);
  let input: HTMLInputElement;
  let list: HTMLDivElement;

  const shown = $derived.by(() => {
    const actionsOnly = query.startsWith(">");
    const q = query.replace(/^>/, "").trim().toLowerCase();
    const out: PaletteItem[] = [];
    for (const it of items) {
      if (actionsOnly && !it.action) continue;
      if (q && !it.label.toLowerCase().includes(q) && !it.sub?.toLowerCase().includes(q)) continue;
      out.push(it);
      if (out.length === MAX_SHOWN) break;
    }
    return out;
  });

  $effect(() => {
    input.focus();
  });

  async function move(by: number) {
    if (!shown.length) return;
    sel = (sel + by + shown.length) % shown.length;
    await tick();
    list.querySelector(".on")?.scrollIntoView({ block: "nearest" });
  }

  function choose(it: PaletteItem | undefined) {
    onclose();
    it?.run();
  }

  function onkeydown(e: KeyboardEvent) {
    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      move(e.key === "ArrowDown" ? 1 : -1);
    } else if (e.key === "Enter") {
      e.preventDefault();
      choose(shown[sel]);
    } else if (e.key === "Escape") {
      e.preventDefault();
      onclose();
    }
  }
</script>

<svelte:window onmousedown={(e) => !(e.target as Element).closest(".pal") && onclose()} />

<div class="pal" role="dialog" aria-label="Go to anything">
  <input
    bind:this={input}
    bind:value={query}
    oninput={() => (sel = 0)}
    {onkeydown}
    {placeholder}
    autocomplete="off"
    spellcheck="false"
    aria-label="Go to anything"
  />
  <div class="plist" bind:this={list}>
    {#each shown as it, i (i)}
      {#if i === 0 || shown[i - 1].section !== it.section}
        <div class="psec">{it.section}</div>
      {/if}
      <button class="pi" class:on={i === sel} onclick={() => choose(it)} onmousemove={() => (sel = i)}>
        <span class="label">{it.label}</span>
        {#if it.sub}<span class="sub">{it.sub}</span>{/if}
      </button>
    {:else}
      <div class="psec">Nothing matches</div>
    {/each}
  </div>
</div>

<style>
  .pal {
    position: fixed;
    z-index: 60;
    top: 84px;
    left: 50%;
    transform: translateX(-50%);
    width: min(600px, calc(100% - 32px));
    background: #16181a;
    border-radius: 10px;
    box-shadow:
      0 0 0 1px #2b2f32,
      0 24px 60px rgba(0, 0, 0, 0.65);
    overflow: hidden;
    font-size: 13px;
  }
  input {
    width: 100%;
    height: 48px;
    padding: 0 18px;
    border: 0;
    border-bottom: 1px solid var(--rule);
    background: transparent;
    font-size: 14.5px;
    color: var(--text-hi);
    outline: none;
  }
  input::placeholder {
    color: var(--text-3);
  }
  .plist {
    max-height: 360px;
    overflow: auto;
    padding: 6px;
  }
  .psec {
    padding: 10px 12px 4px;
    font-size: 12px;
    color: var(--text-3);
  }
  .pi {
    display: flex;
    align-items: center;
    gap: 10px;
    width: 100%;
    height: 34px;
    padding: 0 12px;
    border-radius: 6px;
    white-space: nowrap;
    text-align: left;
    color: var(--text-hi);
  }
  .pi.on {
    background: var(--lift-2);
  }
  .label {
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .sub {
    margin-left: auto;
    padding-left: 16px;
    color: var(--text-3);
    font: 12px var(--font-code);
    overflow: hidden;
    text-overflow: ellipsis;
  }
</style>
