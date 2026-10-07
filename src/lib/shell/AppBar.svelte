<script lang="ts" module>
  export interface MenuItem {
    label: string;
    key?: string;
    disabled?: boolean;
    /** Shows a check, e.g. the current theme. */
    checked?: boolean;
    run: () => void;
  }
  export interface Menu {
    label: string;
    items: (MenuItem | "-")[];
  }
  /** Where you are, shown in the middle of the bar: the file, then package, class, member. */
  export interface Location {
    parts: { label: string; run?: () => void }[];
    back: boolean;
    forward: boolean;
    onback: () => void;
    onforward: () => void;
  }
</script>

<script lang="ts">
  import { getCurrentWindow } from "@tauri-apps/api/window";
  import Icon from "$lib/Icon.svelte";

  let {
    menus,
    location = null,
    bare = false,
    onpalette,
  }: {
    menus: Menu[];
    location?: Location | null;
    /** Only the drag area and window buttons, for onboarding. */
    bare?: boolean;
    onpalette: () => void;
  } = $props();

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
  <div class="l" data-tauri-drag-region>
    {#if !bare}
      <div class="brand" data-tauri-drag-region>
        <svg viewBox="1 12 62 36" aria-hidden="true" width="24" height="14"
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
                {#each menu.items as item, i (item === "-" ? `-${i}` : item.label)}
                  {#if item === "-"}
                    <hr />
                  {:else}
                    <button role="menuitem" disabled={item.disabled} onclick={() => run(item)}>
                      <span class="chk">{#if item.checked}<Icon name="check" size={14} />{/if}</span>
                      <span>{item.label}</span>
                      {#if item.key}<kbd>{item.key}</kbd>{/if}
                    </button>
                  {/if}
                {/each}
              </div>
            {/if}
          </div>
        {/each}
      </nav>
    {/if}
  </div>

  <div class="loc" data-tauri-drag-region>
    {#if location && !bare}
      <button class="nav" title="Back (Alt Left)" disabled={!location.back} onclick={location.onback}><Icon name="arrowLeft" size={16} /></button>
      <button class="nav" title="Forward (Alt Right)" disabled={!location.forward} onclick={location.onforward}><Icon name="arrowRight" size={16} /></button>
      <div class="crumbs">
        {#each location.parts as p, i (i)}
          {#if i > 0}<span class="sep"><Icon name="chevronRight" size={13} /></span>{/if}
          <button class:file={i === 0} class:cur={i > 0 && i === location.parts.length - 1} disabled={!p.run} onclick={p.run}>{p.label}</button>
        {/each}
      </div>
    {/if}
  </div>

  <div class="r" data-tauri-drag-region>
    {#if !bare}
      <button class="search" onclick={onpalette}>
        <Icon name="search" size={15} /><span>Go to anything</span><kbd>Ctrl P</kbd>
      </button>
    {/if}
    <div class="wctl">
      <button title="Minimize" onclick={() => win.minimize()}><Icon name="minus" size={15} /></button>
      <button title="Maximize" onclick={() => win.toggleMaximize()}><Icon name="square" size={12} /></button>
      <button class="close" title="Close" onclick={() => win.close()}><Icon name="x" size={15} /></button>
    </div>
  </div>
</header>

<style>
  .appbar {
    height: 42px;
    flex: none;
    display: grid;
    grid-template-columns: 1fr auto 1fr;
    align-items: center;
    padding-left: 14px;
    background: var(--frame);
  }
  .l,
  .r {
    display: flex;
    align-items: center;
    min-width: 0;
    height: 100%;
  }
  .r {
    justify-content: flex-end;
  }
  .brand {
    display: flex;
    align-items: center;
    gap: 9px;
    margin-right: 14px;
    font: 600 14px/1 var(--font-ui);
    color: var(--text-hi);
    white-space: nowrap;
  }
  .menus {
    display: flex;
  }
  .menu {
    position: relative;
  }
  .menu > button {
    padding: 5px 9px;
    border-radius: 7px;
    color: var(--text-2);
  }
  .menu > button:hover,
  .menu > button.on {
    color: var(--text-hi);
    background: var(--hover);
  }
  .drop {
    position: absolute;
    z-index: 50;
    top: calc(100% + 6px);
    left: 0;
    min-width: 240px;
    padding: 5px;
    background: var(--raised);
    border-radius: 10px;
    box-shadow: var(--shadow);
  }
  .drop button {
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
  }
  .drop button:hover:not(:disabled) {
    background: var(--hover);
    color: var(--text-hi);
  }
  .drop button:disabled {
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
  .drop kbd {
    margin-left: auto;
    padding-left: 24px;
  }
  .drop hr {
    height: 1px;
    margin: 5px 8px;
    border: 0;
    background: var(--line);
  }

  .loc {
    display: flex;
    align-items: center;
    gap: 2px;
    min-width: 0;
    max-width: 52vw;
    height: 100%;
  }
  .nav {
    width: 28px;
    height: 28px;
    flex: none;
    display: grid;
    place-items: center;
    border-radius: 7px;
    color: var(--text-2);
  }
  .nav:hover:not(:disabled) {
    background: var(--hover);
    color: var(--text-hi);
  }
  .nav:disabled {
    color: var(--gutter);
    cursor: default;
  }
  .crumbs {
    display: flex;
    align-items: center;
    min-width: 0;
    margin-left: 6px;
    white-space: nowrap;
    overflow: hidden;
  }
  .crumbs button {
    padding: 4px 7px;
    border-radius: 7px;
    color: var(--text-2);
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .crumbs button:hover:not(:disabled) {
    background: var(--hover);
    color: var(--text-hi);
  }
  .crumbs button:disabled {
    cursor: default;
  }
  .crumbs button.file {
    color: var(--text-hi);
    font-weight: 600;
    flex: none;
  }
  .crumbs button.cur {
    color: var(--text-hi);
    font-weight: 500;
  }
  .sep {
    display: grid;
    color: var(--gutter);
  }

  .search {
    display: flex;
    align-items: center;
    gap: 8px;
    width: 270px;
    min-width: 0;
    height: 30px;
    margin-right: 6px;
    padding: 0 10px;
    border-radius: 9px;
    background: var(--panel);
    border: 1px solid var(--edge);
    color: var(--text-3);
  }
  .search:hover {
    color: var(--text-2);
  }
  .search span {
    flex: 1;
    text-align: left;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .wctl {
    display: flex;
    align-self: stretch;
  }
  .wctl button {
    width: 46px;
    display: grid;
    place-items: center;
    color: var(--text-3);
  }
  .wctl button:hover {
    color: var(--text-hi);
    background: var(--hover);
  }
  .wctl .close:hover {
    background: #c4453c;
    color: #fff;
  }
</style>
