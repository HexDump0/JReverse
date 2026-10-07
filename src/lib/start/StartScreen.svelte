<script lang="ts" module>
  import type { Peek } from "$lib/engine";

  export interface Opening {
    path: string;
    peek: Peek;
    /** What the engine is doing, e.g. "Loading classes with jadx". */
    text: string;
  }
</script>

<script lang="ts">
  // The home screen: the mark, an Open button, and the files you opened before,
  // one line each. The selected one opens up to show where you were and your
  // open tabs, ready to continue. A file can be dropped anywhere in the window.
  import { tick } from "svelte";
  import { getVersion } from "@tauri-apps/api/app";
  import { loadProject, saveProject, type InputKind, type Project, type RecentView } from "$lib/engine";
  import Icon from "$lib/Icon.svelte";
  import type { IconName } from "$lib/icons";
  import { baseName, dirName, fmtN, fmtSize, fmtWhen, kindLabel, tildify } from "$lib/format";

  interface Props {
    recents: RecentView[];
    /** The bundled example app, offered on first run. */
    example: { path: string; peek: Peek } | null;
    home: string | null;
    /** The file just dropped or picked, while it's checked; kept when it can't be opened. */
    readout: Peek | null;
    opening: Opening | null;
    /** Whether keys go to this screen (false while the palette is open). */
    active: boolean;
    onopen: (path: string) => void;
    onbrowse: () => void;
    oncancel: () => void;
    ondismiss: () => void;
    onremove: (r: RecentView) => void;
    onreveal: (r: RecentView) => void;
    onlocate: (r: RecentView) => void;
    onclear: () => void;
  }

  let { recents, example, home, readout, opening, active, onopen, onbrowse, oncancel, ondismiss, onremove, onreveal, onlocate, onclear }: Props = $props();

  const SHOWN = 8;

  let query = $state("");
  let sel = $state(0);
  let all = $state(false);
  let list = $state<HTMLDivElement>();
  let filterInput = $state<HTMLInputElement>();
  let version = $state("");
  getVersion().then((v) => (version = v), () => {});

  // The bundled example is always on the list: the only entry on first run, the last
  // row after that. Once opened it is an ordinary recent file.
  const known = $derived.by(() => {
    if (!example || recents.some((r) => r.path === example.path)) return recents;
    const p = example.peek;
    return [...recents, { path: example.path, kind: p.kind ?? "jar", classCount: p.classes ?? 0, size: p.size, openedAt: 0, missing: false }];
  });

  // A file opened for the first time sits at the top while it loads.
  const entries = $derived.by(() => {
    if (!opening || known.some((r) => r.path === opening.path)) return known;
    const p = opening.peek;
    return [{ path: opening.path, kind: p.kind ?? "jar", classCount: p.classes ?? 0, size: p.size, openedAt: Date.now(), missing: false }, ...known];
  });

  const rows = $derived.by(() => {
    const q = query.trim().toLowerCase();
    if (q) return entries.filter((r) => baseName(r.path).toLowerCase().includes(q));
    return all ? entries : entries.slice(0, SHOWN);
  });

  const isLoading = (r: RecentView) => opening?.path === r.path;
  const isExample = (r: RecentView) => r.path === example?.path && r.openedAt === 0;
  const where = (r: RecentView) => tildify(dirName(r.path), home);

  /* ---------- where you were, for the selected file ---------- */
  const projects = new Map<string, Project | null>();
  let project = $state<Project | null>(null);

  $effect(() => {
    const r = rows[sel];
    project = null;
    if (!r || r.missing || isExample(r)) return;
    const path = r.path;
    if (projects.has(path)) return void (project = projects.get(path)!);
    loadProject(path).then(
      (p) => {
        projects.set(path, p);
        if (rows[sel]?.path === path) project = p;
      },
      () => projects.set(path, null),
    );
  });

  const simple = (id: string) => {
    const s = id.slice(id.lastIndexOf("/") + 1);
    return s.slice(s.lastIndexOf("$") + 1);
  };
  const lastIn = $derived.by(() => {
    const cls = project?.active?.startsWith("class:") ? project.active.slice(6) : null;
    return cls ? (project?.renames[cls] ?? simple(cls)) : null;
  });
  const noteCount = $derived(project ? Object.keys(project.renames).length + Object.keys(project.comments).length + project.bookmarks.length : 0);
  const tabs = $derived((project?.tabs ?? []).slice(0, 4));

  /** Opens the file with a given tab in front. */
  async function openAt(r: RecentView, cls: string | null) {
    if (project && cls !== undefined) {
      const p = { ...$state.snapshot(project), active: cls ? `class:${cls}` : "overview" };
      await saveProject(r.path, p).catch(() => {});
      projects.set(r.path, p);
    }
    onopen(r.path);
  }

  $effect(() => {
    sel = Math.max(0, Math.min(sel, rows.length - 1));
  });

  // Whatever is being opened gets selected.
  $effect(() => {
    const path = opening?.path;
    if (!path) return;
    query = "";
    const k = rows.findIndex((r) => r.path === path);
    if (k >= 0) sel = k;
  });

  async function select(k: number) {
    sel = k;
    await tick();
    list?.querySelector(`[data-sel="${k}"]`)?.scrollIntoView({ block: "nearest" });
  }

  function open(r: RecentView | undefined) {
    if (!r || isLoading(r)) return;
    if (r.missing) onlocate(r);
    else onopen(r.path);
  }

  function onkeydown(e: KeyboardEvent) {
    if (!active) return;
    const mod = e.ctrlKey || e.metaKey;
    const inFilter = e.target === filterInput;
    if (e.key === "Escape") {
      if (opening) oncancel();
      else if (readout) ondismiss();
      else if (query) {
        query = "";
        sel = 0;
        filterInput?.blur();
      }
      return;
    }
    if (!rows.length || opening) return;
    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      select((sel + (e.key === "ArrowDown" ? 1 : rows.length - 1)) % rows.length);
      return;
    }
    if (e.key === "Enter" && !(e.target instanceof HTMLButtonElement)) {
      e.preventDefault();
      open(rows[sel]);
      return;
    }
    if (inFilter || mod || e.altKey) return;
    if (e.key === "Delete" || e.key === "Backspace") {
      e.preventDefault();
      const r = rows[sel];
      if (r && !isExample(r)) onremove(r);
      return;
    }
    if (e.key.length === 1 && e.key !== " " && filterInput) filterInput.focus();
  }

  const ICON: Record<InputKind, IconName> = { apk: "android", aab: "android", dex: "fileCode", jar: "coffee", aar: "box", class: "coffee" };
  const TINT: Record<InputKind, string> = { apk: "var(--ok)", aab: "var(--ok)", dex: "var(--c-type)", jar: "var(--c-number)", aar: "var(--c-keyword)", class: "var(--c-number)" };

  const hex = (b: number) => b.toString(16).toUpperCase().padStart(2, "0");
  const ascii = (b: number) => (b >= 0x20 && b < 0x7f ? String.fromCharCode(b) : ".");
</script>

<svelte:window {onkeydown} />

<div class="home island">
  <div class="in">
    <header class="hero">
      <svg class="mark" viewBox="1 12 62 36" aria-hidden="true"
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
      >
      <div>
        <h1>JReverse</h1>
        {#if version}<p>Version {version}</p>{/if}
      </div>
      <span class="sp"></span>
      <button class="btn" onclick={onbrowse}><Icon name="folderOpen" size={16} />Open file<kbd>Ctrl O</kbd></button>
    </header>

    {#if readout?.problem}
      {@const head = readout.head.slice(0, 8)}
      <div class="problem" role="alert">
        <Icon name="alert" size={18} />
        <div class="pb">
          <b>Can't open {readout.name}: {readout.problem.title}</b>
          <p>{readout.problem.text}</p>
          <p class="hex">
            <span class="dim">{fmtSize(readout.size)}, starts with</span>
            <code>{head.map(hex).join(" ")}</code>
            <code class="dim">{head.map(ascii).join("")}</code>
          </p>
        </div>
        <button class="ib" title="Dismiss (Esc)" onclick={ondismiss}><Icon name="x" size={15} /></button>
      </div>
    {/if}

    {#if entries.length}
      <div class="head">
        <h2>{recents.length ? "Recent" : "Start with the example"}</h2>
        {#if recents.length}<span class="n">{fmtN(recents.length)}</span>{/if}
        {#if entries.length > SHOWN}
          <label class="field">
            <Icon name="filter" size={14} />
            <input bind:this={filterInput} bind:value={query} oninput={() => (sel = 0)} placeholder="Filter" autocomplete="off" spellcheck="false" aria-label="Filter recent files" />
          </label>
        {/if}
      </div>

      <div class="list" role="listbox" aria-label="Recent files" bind:this={list}>
        {#each rows as r, k (r.path)}
          {@const on = sel === k}
          <!-- svelte-ignore a11y_click_events_have_key_events -->
          <div
            class="rec"
            class:on
            class:missing={r.missing}
            data-sel={k}
            role="option"
            aria-selected={on}
            tabindex="-1"
            onclick={() => (on ? open(r) : select(k))}
            ondblclick={() => open(r)}
          >
            <span class="ic" style:color={TINT[r.kind]} style:background="color-mix(in srgb, {TINT[r.kind]} 16%, var(--panel))"><Icon name={ICON[r.kind]} size={21} /></span>
            <div class="tx">
              <div class="t1">
                <b>{baseName(r.path)}</b>
                <span class="sub">{isExample(r) ? "Example app, bundled with JReverse" : where(r)}</span>
                <span class="when">
                  {#if isLoading(r)}<i class="spin"></i>{:else if !isExample(r)}{fmtWhen(r.openedAt)}{/if}
                </span>
                {#if !isExample(r) && !isLoading(r)}
                  <span class="acts">
                    <button class="ib" title="Show in folder" onclick={(e) => (e.stopPropagation(), onreveal(r))}><Icon name="folder" size={15} /></button>
                    <button class="ib" title="Remove from the list (Del)" onclick={(e) => (e.stopPropagation(), onremove(r))}><Icon name="x" size={15} /></button>
                  </span>
                {/if}
              </div>
              {#if on}
                {#if isLoading(r)}
                  <div class="more"><span class="dim">{opening?.text}</span><span class="go dim">Esc to cancel</span></div>
                {:else if r.missing}
                  <div class="more">
                    <span class="bad">Moved or deleted</span>
                    <span class="go">
                      <button class="btn" onclick={(e) => (e.stopPropagation(), onremove(r))}>Remove</button>
                      <button class="btn primary" onclick={(e) => (e.stopPropagation(), onlocate(r))}>Locate<kbd>Enter</kbd></button>
                    </span>
                  </div>
                {:else}
                  <div class="facts">
                    {kindLabel(r.kind)}{#if r.size > 1}, {fmtSize(r.size)}{/if}{#if r.classCount > 0}, {fmtN(r.classCount)} classes{/if}
                    {#if lastIn}<span class="was">You were in <code>{lastIn}</code></span>{/if}
                    {#if noteCount}<span class="dim">{noteCount} {noteCount === 1 ? "note" : "notes"}</span>{/if}
                  </div>
                  <div class="more">
                    {#each tabs as t (t.cls)}
                      <button class="otab" title="Open with {simple(t.cls)} in front" onclick={(e) => (e.stopPropagation(), openAt(r, t.cls))}>
                        <span class="k k-c">C</span>{project?.renames[t.cls] ?? simple(t.cls)}
                      </button>
                    {/each}
                    <button class="btn primary go" onclick={(e) => (e.stopPropagation(), open(r))}>{tabs.length ? "Continue" : "Open"}<kbd>Enter</kbd></button>
                  </div>
                {/if}
              {/if}
            </div>
          </div>
        {:else}
          <p class="none">Nothing matches {query}</p>
        {/each}
      </div>

      <div class="foot">
        <span>Drop an APK, AAB, AAR, JAR, WAR, DEX or class file anywhere in this window.</span>
        {#if !query && entries.length > SHOWN}
          <button class="lnk" onclick={() => (all = !all)}>{all ? "Show fewer" : `Show all ${entries.length}`}</button>
        {/if}
        {#if recents.length > 1}<button class="lnk" onclick={onclear}>Clear recent files</button>{/if}
      </div>
    {:else}
      <p class="none">Drop an APK, AAB, AAR, JAR, WAR, DEX or class file anywhere in this window, or open one.</p>
    {/if}
  </div>
</div>

<style>
  .home {
    flex: 1;
    margin: 0 6px;
    overflow: auto;
    background: var(--editor);
  }
  .in {
    width: 100%;
    max-width: 840px;
    margin: 0 auto;
    padding: 9vh 32px 48px;
  }
  .hero {
    display: flex;
    align-items: center;
    gap: 18px;
    padding: 0 14px 34px;
  }
  .mark {
    width: 64px;
    height: 37px;
    flex: none;
  }
  h1 {
    margin: 0;
    font: 600 26px/1.1 var(--font-ui);
    letter-spacing: -0.02em;
    color: var(--text-hi);
  }
  .hero p {
    margin: 5px 0 0;
    color: var(--text-3);
  }
  .sp {
    flex: 1;
  }
  .problem {
    display: flex;
    align-items: flex-start;
    gap: 12px;
    margin: 0 0 24px;
    padding: 14px 12px 14px 16px;
    border-radius: 12px;
    background: color-mix(in srgb, var(--bad) 10%, var(--panel));
    color: var(--bad);
  }
  .problem :global(svg) {
    margin-top: 1px;
  }
  .pb {
    flex: 1;
    min-width: 0;
    color: var(--text);
  }
  .pb b {
    font-weight: 600;
    color: var(--text-hi);
  }
  .pb p {
    margin: 4px 0 0;
    color: var(--text-2);
  }
  .hex {
    display: flex;
    gap: 10px;
    flex-wrap: wrap;
  }
  .hex code {
    font-size: 12.5px;
    color: var(--text);
  }
  .head {
    display: flex;
    align-items: center;
    gap: 8px;
    margin: 0 0 8px;
    padding: 0 14px;
    height: 32px;
  }
  h2 {
    margin: 0;
    font: 600 14px var(--font-ui);
    color: var(--text-2);
  }
  .head .n {
    color: var(--text-3);
  }
  .head .field {
    margin: 0 0 0 auto;
    width: 220px;
    height: 30px;
  }
  .list {
    display: flex;
    flex-direction: column;
    gap: 3px;
  }
  .rec {
    display: flex;
    align-items: flex-start;
    gap: 16px;
    padding: 11px 14px;
    border-radius: 13px;
    border: 1px solid transparent;
    cursor: pointer;
    outline: none;
  }
  .rec:hover {
    background: var(--panel);
  }
  .rec.on {
    background: var(--panel);
    border-color: var(--edge);
    padding: 16px 16px 16px 14px;
  }
  .ic {
    width: 42px;
    height: 42px;
    flex: none;
    display: grid;
    place-items: center;
    border-radius: 11px;
  }
  .missing .ic {
    filter: grayscale(1);
    opacity: 0.6;
  }
  .tx {
    flex: 1;
    min-width: 0;
    padding-top: 1px;
  }
  .t1 {
    display: flex;
    align-items: baseline;
    gap: 10px;
    min-height: 40px;
    white-space: nowrap;
  }
  .rec.on .t1 {
    min-height: 0;
  }
  .t1 b {
    font-weight: 600;
    font-size: 15px;
    color: var(--text-hi);
    overflow: hidden;
    text-overflow: ellipsis;
    align-self: center;
  }
  .rec.on .t1 b {
    align-self: baseline;
  }
  .missing .t1 b {
    color: var(--text-3);
    text-decoration: line-through;
  }
  .sub {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    font: 12.5px var(--font-code);
    color: var(--text-3);
    align-self: center;
  }
  .rec.on .sub {
    align-self: baseline;
  }
  .when {
    margin-left: auto;
    flex: none;
    color: var(--text-3);
    align-self: center;
  }
  .rec.on .when {
    align-self: baseline;
  }
  .acts {
    display: none;
    gap: 2px;
    align-self: center;
    margin: -6px 0;
  }
  .rec:hover .acts {
    display: flex;
  }
  .rec:hover .when {
    display: none;
  }
  .facts {
    margin-top: 4px;
    color: var(--text-3);
    display: flex;
    flex-wrap: wrap;
    gap: 0 14px;
  }
  .was {
    color: var(--text-2);
  }
  .was code {
    font-size: 13px;
    font-weight: 500;
    color: var(--text-hi);
  }
  .more {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 6px;
    margin-top: 12px;
  }
  .go {
    margin-left: auto;
    display: flex;
    gap: 6px;
  }
  .otab {
    display: flex;
    align-items: center;
    gap: 7px;
    height: 30px;
    padding: 0 11px 0 8px;
    border-radius: 8px;
    background: var(--editor);
    border: 1px solid var(--edge);
    color: var(--text);
  }
  .otab:hover {
    border-color: var(--gutter);
    color: var(--text-hi);
  }
  .dim {
    color: var(--text-3);
  }
  .bad {
    color: var(--bad);
  }
  .none {
    margin: 0;
    padding: 12px 14px;
    color: var(--text-3);
  }
  .foot {
    display: flex;
    flex-wrap: wrap;
    gap: 6px 18px;
    margin-top: 20px;
    padding: 0 14px;
    color: var(--text-3);
  }
  .lnk {
    color: var(--text-2);
    text-decoration: underline;
    text-decoration-color: var(--gutter);
    text-underline-offset: 3px;
  }
  .lnk:hover {
    color: var(--text-hi);
  }
</style>
