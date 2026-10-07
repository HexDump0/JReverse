<script lang="ts">
  // A right-click menu at the pointer, styled like the app bar's menus.
  import { tick } from "svelte";
  import Icon from "$lib/Icon.svelte";
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
        <span class="chk">{#if item.checked}<Icon name="check" size={14} />{/if}</span>
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
    padding: 5px;
    background: var(--raised);
    border-radius: 10px;
    box-shadow: var(--shadow);
    outline: none;
  }
  button {
    display: flex;
    align-items: center;
    gap: 6px;
    width: 100%;
    height: 30px;
    padding: 0 10px 0 4px;
    border-radius: 7px;
    text-align: left;
    color: var(--text);
    white-space: nowrap;
    outline: none;
  }
  button:hover:not(:disabled),
  button:focus-visible {
    background: var(--hover);
    color: var(--text-hi);
  }
  button:disabled {
    color: var(--text-3);
    opacity: 0.6;
    cursor: default;
  }
  .chk {
    width: 18px;
    display: grid;
    place-items: center;
    color: var(--accent);
  }
  kbd {
    margin-left: auto;
    padding-left: 24px;
  }
  .sep {
    height: 1px;
    margin: 5px 8px;
    background: var(--line);
  }
</style>
