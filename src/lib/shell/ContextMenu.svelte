<script lang="ts">
  // A right-click menu at the pointer, styled like the app bar's menus.
  import { tick } from "svelte";
  import type { MenuItem } from "./AppBar.svelte";

  let { x, y, items, onclose }: { x: number; y: number; items: (MenuItem | "-")[]; onclose: () => void } = $props();

  let el: HTMLDivElement;
  let pos = $state({ left: 0, top: 0 });

  $effect(() => {
    void [x, y];
    pos = { left: x, top: y };
    tick().then(() => {
      // Keep it inside the window.
      const r = el.getBoundingClientRect();
      pos = { left: Math.min(x, innerWidth - r.width - 8), top: Math.min(y, innerHeight - r.height - 8) };
      el.querySelector<HTMLButtonElement>("button:not(:disabled)")?.focus();
    });
  });

  function run(item: MenuItem) {
    onclose();
    item.run();
  }

  function onkeydown(e: KeyboardEvent) {
    const buttons = [...el.querySelectorAll<HTMLButtonElement>("button:not(:disabled)")];
    const i = buttons.indexOf(document.activeElement as HTMLButtonElement);
    if (e.key === "Escape") onclose();
    else if (e.key === "ArrowDown") buttons[(i + 1) % buttons.length]?.focus();
    else if (e.key === "ArrowUp") buttons[(i - 1 + buttons.length) % buttons.length]?.focus();
    else return;
    e.preventDefault();
    e.stopPropagation();
  }
</script>

<svelte:window onmousedown={(e) => !el.contains(e.target as Node) && onclose()} onblur={onclose} />

<div class="ctx" role="menu" tabindex="-1" bind:this={el} style:left="{pos.left}px" style:top="{pos.top}px" {onkeydown}>
  {#each items as item, i (i)}
    {#if item === "-"}
      <div class="sep"></div>
    {:else}
      <button role="menuitem" disabled={item.disabled} onclick={() => run(item)}>
        <span>{item.label}</span>
        {#if item.key}<kbd>{item.key}</kbd>{/if}
      </button>
    {/if}
  {/each}
</div>

<style>
  .ctx {
    position: fixed;
    z-index: 70;
    min-width: 230px;
    padding: 4px;
    background: #16181a;
    border-radius: 8px;
    box-shadow:
      0 0 0 1px #2b2f32,
      0 16px 40px rgba(0, 0, 0, 0.6);
    outline: none;
    font-size: 12.5px;
  }
  button {
    display: flex;
    align-items: center;
    gap: 24px;
    width: 100%;
    height: 28px;
    padding: 0 10px;
    border-radius: 5px;
    text-align: left;
    color: var(--text);
    white-space: nowrap;
    outline: none;
  }
  button:hover:not(:disabled),
  button:focus-visible {
    background: var(--lift-2);
  }
  button:disabled {
    color: var(--faint);
    cursor: default;
  }
  kbd {
    margin-left: auto;
    font: 11.5px var(--font-code);
    color: var(--text-3);
  }
  .sep {
    height: 1px;
    margin: 4px 6px;
    background: var(--rule);
  }
</style>
