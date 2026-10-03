<script lang="ts" module>
  export interface MenuItem {
    label: string;
    key?: string;
    disabled?: boolean;
    run: () => void;
  }
  export interface Menu {
    label: string;
    items: MenuItem[];
  }
</script>

<script lang="ts">
  import { getCurrentWindow } from "@tauri-apps/api/window";
  import Icon from "$lib/Icon.svelte";

  let {
    menus,
    bare = false,
    onpalette,
  }: { menus: Menu[]; /** Only the drag area and window buttons, for onboarding. */ bare?: boolean; onpalette: () => void } =
    $props();

  let openMenu = $state<string | null>(null);

  // The window has no OS title bar (tauri.conf.json), so this bar drags it
  // (data-tauri-drag-region, double-click maximizes) and holds its buttons.
  const win = getCurrentWindow();

  function run(item: MenuItem) {
    openMenu = null;
    item.run();
  }

  function onWindowDown(e: MouseEvent) {
    if (openMenu && !(e.target as Element).closest(".menus")) openMenu = null;
  }

  function onWindowKey(e: KeyboardEvent) {
    if (openMenu && e.key === "Escape") {
      e.preventDefault();
      e.stopImmediatePropagation();
      openMenu = null;
    }
  }
</script>

<svelte:window onmousedown={onWindowDown} onkeydowncapture={onWindowKey} />

<header class="appbar" class:bare data-tauri-drag-region>
  {#if !bare}
  <div class="brand" data-tauri-drag-region>
    <svg viewBox="1 12 62 36" aria-hidden="true" width="26" height="15"
      ><polygon points="2.50,33.90 25.64,26.72 25.64,29.92 2.50,37.10" fill="#cdd7fa" /><polygon
        points="38.75,29.20 61.50,19.40 61.50,22.60 38.75,32.40"
        fill="#cdd7fa"
      /><polygon points="38.75,29.20 61.50,25.90 61.50,29.10 38.75,32.40" fill="#a4b7f1" /><polygon
        points="38.75,29.20 61.50,32.40 61.50,35.60 38.75,32.40"
        fill="#6f82cf"
      /><polygon points="38.75,29.20 61.50,38.90 61.50,42.10 38.75,32.40" fill="#4f5f9e" /><polygon
        points="32.00,13.00 32.00,47.00 12.50,47.00"
        fill="#cdd7fa"
      /><polygon points="32.00,13.00 51.50,47.00 32.00,47.00" fill="#6f82cf" /></svg
    >JReverse
  </div>
  <nav class="menus">
    {#each menus as menu (menu.label)}
      <div class="menu">
        <button
          class:on={openMenu === menu.label}
          onclick={() => (openMenu = openMenu === menu.label ? null : menu.label)}
          onmouseenter={() => openMenu && (openMenu = menu.label)}
          aria-haspopup="menu"
          aria-expanded={openMenu === menu.label}>{menu.label}</button
        >
        {#if openMenu === menu.label}
          <div class="drop" role="menu">
            {#each menu.items as item (item.label)}
              <button role="menuitem" disabled={item.disabled} onclick={() => run(item)}>
                <span>{item.label}</span>
                {#if item.key}<kbd>{item.key}</kbd>{/if}
              </button>
            {/each}
          </div>
        {/if}
      </div>
    {/each}
  </nav>
  {/if}
  <div class="drag" data-tauri-drag-region></div>
  {#if !bare}
    <button class="search" onclick={onpalette}>
      <Icon name="search" size={14} /><span>Go to anything</span><kbd>Ctrl P</kbd>
    </button>
  {/if}
  <div class="wctl">
    <button title="Minimize" onclick={() => win.minimize()}><Icon name="minus" size={15} /></button>
    <button title="Maximize" onclick={() => win.toggleMaximize()}><Icon name="square" size={12} /></button>
    <button class="close" title="Close" onclick={() => win.close()}><Icon name="x" size={15} /></button>
  </div>
</header>

<style>
  .appbar {
    height: 38px;
    flex: none;
    display: flex;
    align-items: center;
    gap: 18px;
    padding: 0 0 0 14px;
    background: var(--ground);
    user-select: none;
    border-bottom: 1px solid var(--line);
    font-size: 13px;
  }
  .appbar.bare {
    background: var(--void);
    border-bottom-color: transparent;
  }
  .brand {
    display: flex;
    align-items: center;
    gap: 8px;
    font: 600 13px/1 var(--font-ui);
    letter-spacing: -0.01em;
    color: var(--text-hi);
  }
  .menus {
    display: flex;
    height: 100%;
  }
  .menu {
    position: relative;
    display: flex;
  }
  .menu > button {
    display: flex;
    align-items: center;
    padding: 0 9px;
    color: var(--muted);
    font-size: 12.5px;
  }
  .menu > button:hover,
  .menu > button.on {
    color: var(--text);
    background: #181b1c;
  }
  .drop {
    position: absolute;
    z-index: 50;
    top: 100%;
    left: 0;
    min-width: 220px;
    padding: 4px;
    background: #16181a;
    border-radius: 8px;
    box-shadow:
      0 0 0 1px #2b2f32,
      0 16px 40px rgba(0, 0, 0, 0.6);
  }
  .drop button {
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
  }
  .drop button:hover:not(:disabled) {
    background: var(--lift-2);
  }
  .drop button:disabled {
    color: var(--faint);
    cursor: default;
  }
  .drop kbd {
    margin-left: auto;
    font: 11.5px var(--font-code);
    color: var(--text-3);
  }
  .drag {
    flex: 1;
    align-self: stretch;
  }
  .search {
    display: flex;
    align-items: center;
    gap: 8px;
    width: 320px;
    min-width: 0;
    height: 24px;
    padding: 0 8px 0 9px;
    border: 1px solid var(--line-2);
    border-radius: 4px;
    background: #0e1011;
    color: var(--faint);
    font-size: 12.5px;
  }
  .search:hover {
    color: var(--muted);
  }
  .search span {
    flex: 1;
    text-align: left;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .search kbd {
    font: 11px var(--font-code);
  }
  .wctl {
    display: flex;
    align-self: stretch;
    margin-left: -8px;
  }
  .wctl button {
    width: 44px;
    display: grid;
    place-items: center;
    color: var(--muted);
  }
  .wctl button:hover {
    color: var(--text-hi);
    background: #222527;
  }
  .wctl .close:hover {
    background: #c4453c;
    color: #fff;
  }
</style>
