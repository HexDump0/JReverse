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
  // The start screen from design/start-mockup.html. Left: the dark stage where a
  // file goes in (the beam). The prism sits on the seam. Right: the shelf of
  // files opened before; a faint ray from the prism lands on the selected one.
  import { tick } from "svelte";
  import type { RecentView } from "$lib/engine";
  import Icon from "$lib/Icon.svelte";
  import Dots from "$lib/Dots.svelte";
  import { baseName, dirName, fmtN, fmtSize, fmtWhen, kindLabel, tildify } from "$lib/format";

  interface Props {
    recents: RecentView[];
    /** The bundled example app, offered on first run. */
    example: { path: string; peek: Peek } | null;
    /** Where the Continue file was left: the class in its active tab, and how many notes it has. */
    resume: { path: string; at: string | null; notes: number } | null;
    home: string | null;
    /** The file just dropped or picked, shown under the beam. */
    readout: Peek | null;
    opening: Opening | null;
    drag: boolean;
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

  let {
    recents,
    example,
    resume,
    home,
    readout,
    opening,
    drag,
    active,
    onopen,
    onbrowse,
    oncancel,
    ondismiss,
    onremove,
    onreveal,
    onlocate,
    onclear,
  }: Props = $props();

  let query = $state("");
  let sel = $state(0);
  let work: HTMLDivElement;
  let stage: HTMLElement;
  let anchor: HTMLDivElement;
  let shelf: HTMLElement;
  let filterInput = $state<HTMLInputElement>();

  // The bundled example is always on the list: the default on first run, the last
  // row after that. Once opened it is an ordinary recent file.
  const known = $derived.by(() => {
    if (!example || recents.some((r) => r.path === example.path)) return recents;
    const p = example.peek;
    return [...recents, { path: example.path, kind: p.kind ?? "jar", classCount: p.classes ?? 0, size: p.size, openedAt: 0, missing: false }];
  });

  // A file opened for the first time sits in the list while it loads.
  const entries = $derived.by(() => {
    if (!opening || known.some((r) => r.path === opening.path)) return known;
    const p = opening.peek;
    const fresh: RecentView = {
      path: opening.path,
      kind: p.kind ?? "jar",
      classCount: p.classes ?? 0,
      size: p.size,
      openedAt: Date.now(),
      missing: false,
    };
    return known.length ? [known[0], fresh, ...known.slice(1)] : [fresh];
  });

  const rows = $derived.by(() => {
    const q = query.trim().toLowerCase();
    const rest = entries.slice(1);
    return q ? rest.filter((r) => r.path.toLowerCase().includes(q)) : rest;
  });

  const itemAt = (k: number) => (k === 0 ? entries[0] : rows[k - 1]);
  const isLoading = (r: RecentView) => opening?.path === r.path;
  const isExample = (r: RecentView) => r.path === example?.path && r.openedAt === 0;
  const dirOf = (r: RecentView) => tildify(dirName(r.path), home);
  const blocked = $derived(!!readout?.problem);

  $effect(() => {
    sel = Math.max(0, Math.min(sel, rows.length));
  });

  // Whatever is being opened gets selected, so the ray lands on it.
  $effect(() => {
    const path = opening?.path;
    if (!path) return;
    query = "";
    const k = entries.findIndex((r) => r.path === path);
    if (k >= 0) sel = k;
  });

  async function select(k: number) {
    sel = k;
    await tick();
    if (k > 0) shelf.querySelector(`[data-sel="${k}"]`)?.scrollIntoView({ block: "nearest" });
  }

  function open(r: RecentView | undefined) {
    if (!r || isLoading(r)) return;
    onopen(r.path);
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
    if (!entries.length || opening) return;
    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      const n = rows.length + 1;
      select((sel + (e.key === "ArrowDown" ? 1 : n - 1)) % n);
      return;
    }
    if (e.key === "Enter" && !(e.target instanceof HTMLButtonElement)) {
      e.preventDefault();
      open(itemAt(sel));
      return;
    }
    if (inFilter || mod || e.altKey) return;
    if ((e.key === "Delete" || e.key === "Backspace") && sel > 0) {
      e.preventDefault();
      const r = itemAt(sel);
      if (r && !isExample(r)) onremove(r);
      return;
    }
    if (e.key.length === 1 && e.key !== " ") filterInput?.focus();
  }

  /* ---------- the beam, the prism and the ray to the selected file ---------- */
  let fx = $state({ lit: "", shade: "", bx0: 0, bx1: 0, by: 0, ray: "", rx0: 0, rx1: 0, ry: 0 });

  function layout() {
    if (!work) return;
    const W = work.getBoundingClientRect();
    const st = stage.getBoundingClientRect();
    const an = anchor.getBoundingClientRect();
    const yb = an.top - W.top;
    const x0 = an.left - W.left;
    const seam = st.right - W.left;
    const pw = 52;
    const ph = 46;
    const top = yb - 0.62 * ph;
    const inX = seam - (0.62 * pw) / 2;
    const ye = top + 0.55 * ph;
    const outX = seam + (0.55 * pw) / 2;
    const next = {
      lit: `${seam},${top} ${seam},${top + ph} ${seam - pw / 2},${top + ph}`,
      shade: `${seam},${top} ${seam + pw / 2},${top + ph} ${seam},${top + ph}`,
      bx0: x0,
      bx1: inX,
      by: yb,
      ray: "",
      rx0: outX,
      rx1: outX,
      ry: ye,
    };
    // The ray lands on whatever is selected, if it is on screen.
    const target = !blocked && shelf.querySelector(`[data-sel="${sel}"]`);
    if (target) {
      const sh = shelf.getBoundingClientRect();
      const t = target.getBoundingClientRect();
      const mid = (t.top + t.bottom) / 2;
      if (mid > sh.top + 8 && mid < sh.bottom - 8) {
        const inset = Math.min(14, t.height * 0.25);
        const x1 = t.left - W.left;
        next.ray = `${outX},${ye - 1} ${x1},${t.top - W.top + inset} ${x1},${t.bottom - W.top - inset} ${outX},${ye + 1}`;
        next.rx1 = x1;
      }
    }
    fx = next;
  }

  $effect(() => {
    // Re-layout after anything that moves the anchor or the selected row.
    void [sel, rows, entries, readout, drag, opening?.path, blocked];
    tick().then(layout);
  });

  $effect(() => {
    const ro = new ResizeObserver(layout);
    ro.observe(work);
    document.fonts?.ready.then(layout);
    return () => ro.disconnect();
  });

  const hex = (b: number) => b.toString(16).toUpperCase().padStart(2, "0");
  const ascii = (b: number) => (b >= 0x20 && b < 0x7f ? String.fromCharCode(b) : ".");
</script>

<svelte:window {onkeydown} />

<div class="work" class:hot={drag || !!opening} class:blocked bind:this={work}>
  <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_noninteractive_element_interactions -->
  <section
    class="stage"
    aria-label="Open a file"
    bind:this={stage}
    onscroll={layout}
    onclick={(e) => !(e.target as Element).closest("button") && onbrowse()}
  >
    <h1 class="hero">{drag ? "Let go to open." : "Drop a file to decompile."}</h1>
    <p class="sub">or <button class="pick" onclick={onbrowse}>choose one</button><span class="hk">Ctrl+O</span></p>
    <p class="fmt"><Dots parts={["APK", "AAB", "AAR", "JAR", "WAR", "DEX", "class"]} /></p>
    <div class="anchor" bind:this={anchor}></div>
    {#if readout}
      {@const head = readout.head.slice(0, 8)}
      <div class="ro" class:bad={!!readout.problem}>
        <div class="ro-h"><span class="ro-n">{readout.name}</span><span class="ro-s">{fmtSize(readout.size)}</span></div>
        <div class="hex">
          <span class="off">00000000</span>
          <span><span class="mg">{head.slice(0, 4).map(hex).join(" ")}</span> {head.slice(4).map(hex).join(" ")}</span>
          <span class="as">{head.map(ascii).join("")}</span>
        </div>
        {#if readout.problem}
          <div class="ro-r">{readout.problem.title}</div>
          <p class="ro-e">{readout.problem.text}</p>
        {:else if readout.kind}
          <div class="ro-r"><Icon name="arrowRight" size={14} /><span><Dots parts={[kindLabel(readout.kind), readout.detail]} /></span></div>
        {/if}
      </div>
    {/if}
  </section>

  <!-- svelte-ignore a11y_click_events_have_key_events -->
  <section class="shelf" aria-label="Recent files" bind:this={shelf} onscroll={layout}>
    <div class="shelf-in">
    {#if entries.length}
      {@const first = entries[0]}
      {@const ex = isExample(first)}
      {@const left = resume?.path === first.path ? resume : null}
      <div
        class="cont"
        class:sel={sel === 0}
        class:loading={isLoading(first)}
        data-sel="0"
        role="option"
        aria-selected={sel === 0}
        tabindex="-1"
        onclick={() => {
          select(0);
          open(first);
        }}
      >
        <div class="c-top"><span>{ex ? "Example" : "Continue"}</span>{#if !ex}<span>{fmtWhen(first.openedAt)}</span>{/if}</div>
        <div class="c-name">{baseName(first.path)}</div>
        <div class="c-id" title={first.path}>
          <Dots parts={[!ex && dirOf(first), first.classCount > 0 && `${fmtN(first.classCount)} classes`]} />
        </div>
        <div class="c-at">
          {#if left?.at}<span>Last in <b>{left.at}</b></span>{/if}
          {#if left?.notes}<span>{left.notes} {left.notes === 1 ? "note" : "notes"}</span>{/if}
        </div>
        <div class="c-foot">
          {#if isLoading(first)}
            <span class="c-load">{opening?.text}</span><span class="c-go"><i class="spin"></i>Esc to cancel</span>
          {:else if first.missing}
            <span class="c-miss">Moved or deleted</span>
            <span class="c-acts">
              <button class="tb" onclick={(e) => (e.stopPropagation(), onlocate(first))}>Locate</button>
              <button class="tb" onclick={(e) => (e.stopPropagation(), onremove(first))}>Remove</button>
            </span>
          {:else}
            <span><Dots parts={[kindLabel(first.kind), first.size > 0 && fmtSize(first.size)]} /></span>
            <span class="c-go"><span class="key">Enter</span> to {ex ? "open" : "resume"}</span>
          {/if}
        </div>
      </div>

      {#if entries.length > 1}
      <div class="rhead">
        <h2>Recent</h2>
        <span class="n">{entries.length - 1}</span>
        <label class="filter">
          <Icon name="search" size={14} />
          <input
            bind:this={filterInput}
            bind:value={query}
            oninput={() => (sel = query ? 1 : 0)}
            placeholder="Filter"
            autocomplete="off"
            spellcheck="false"
            aria-label="Filter recent files"
          />
        </label>
      </div>
      <div class="cols" aria-hidden="true">
        <span>Name</span><span class="ck">Type</span><span class="r">Classes</span><span class="r">Opened</span>
      </div>
      <div role="listbox" aria-label="Recent files">
        {#each rows as r, i (r.path)}
          {@const k = i + 1}
          <div
            class="row"
            class:sel={sel === k}
            class:missing={r.missing}
            class:loading={isLoading(r)}
            data-sel={k}
            role="option"
            aria-selected={sel === k}
            tabindex="-1"
            onclick={() => {
              select(k);
              open(r);
            }}
          >
            <div class="r-main" title={r.path}>
              <div class="r-name"><span>{baseName(r.path)}</span></div>
              <div class="r-sub">{isLoading(r) ? opening?.text : r.missing ? "Moved or deleted" : isExample(r) ? "Example" : dirOf(r)}</div>
            </div>
            <span class="r-kind">{r.kind.toUpperCase()}</span>
            <span class="r-n">{r.classCount > 0 ? fmtN(r.classCount) : ""}</span>
            <span class="r-when">
              {#if isLoading(r)}
                <i class="spin"></i>
              {:else if !isExample(r)}
                <span class="t">{fmtWhen(r.openedAt)}</span>
                <span class="acts">
                  {#if r.missing}
                    <button class="tb" onclick={(e) => (e.stopPropagation(), onlocate(r))}>Locate</button>
                    <button class="tb" onclick={(e) => (e.stopPropagation(), onremove(r))}>Remove</button>
                  {:else}
                    <button class="ib" title="Show in folder" onclick={(e) => (e.stopPropagation(), onreveal(r))}>
                      <Icon name="folder" />
                    </button>
                    <button class="ib" title="Remove from list (Del)" onclick={(e) => (e.stopPropagation(), onremove(r))}>
                      <Icon name="x" />
                    </button>
                  {/if}
                </span>
              {/if}
            </span>
          </div>
        {:else}
          <div class="none">{query ? `Nothing matches ${query}` : "No other files yet"}</div>
        {/each}
      </div>
      <div class="sfoot">
        <span class="keys"><span class="key"><Icon name="arrowUp" size={12} /></span><span class="key"><Icon name="arrowDown" size={12} /></span>select</span>
        <span class="keys"><span class="key">Enter</span>open</span>
        <span class="keys"><span class="key">Del</span>remove</span>
        {#if recents.length > 1}<button class="clr" onclick={onclear}>Clear recent files</button>{/if}
      </div>
      {/if}
    {/if}
    </div>
  </section>

  <svg class="fx" aria-hidden="true">
    <defs>
      <linearGradient id="gBeam" gradientUnits="userSpaceOnUse" x1={fx.bx0} y1={fx.by} x2={fx.bx1} y2={fx.by}>
        <stop offset="0" stop-color="#cdd7fa" stop-opacity="0" />
        <stop offset=".6" stop-color="#cdd7fa" stop-opacity=".3" />
        <stop offset="1" stop-color="#cdd7fa" stop-opacity=".9" />
      </linearGradient>
      <linearGradient id="gRay" gradientUnits="userSpaceOnUse" x1={fx.rx0} y1={fx.ry} x2={fx.rx1} y2={fx.ry}>
        <stop offset="0" stop-color="#cdd7fa" stop-opacity=".34" />
        <stop offset=".45" stop-color="#a4b7f1" stop-opacity=".1" />
        <stop offset="1" stop-color="#a4b7f1" stop-opacity=".02" />
      </linearGradient>
    </defs>
    <line class="beam" x1={fx.bx0} y1={fx.by} x2={fx.bx1} y2={fx.by} stroke="url(#gBeam)" stroke-linecap="round" />
    <polygon class="ray" points={fx.ray} fill="url(#gRay)" />
    <g class="prism"><polygon points={fx.lit} fill="#cdd7fa" /><polygon points={fx.shade} fill="#6f82cf" /></g>
  </svg>
</div>

<style>
  .work {
    flex: 1;
    min-height: 0;
    display: grid;
    grid-template-columns: minmax(400px, 40%) minmax(0, 1fr);
    position: relative;
    font: 13px/1.5 var(--font-ui);
    color: var(--text-hi);
  }
  .stage {
    background: var(--void);
    padding: clamp(48px, 17vh, 150px) 96px 48px 60px;
    display: flex;
    flex-direction: column;
    overflow: auto;
    min-width: 0;
    cursor: pointer;
  }
  .stage:hover {
    background: #0c0e0f;
  }
  .hot .stage {
    background: #0d0f13;
  }
  .shelf {
    background: var(--shelf);
    padding: 52px 52px 40px 84px;
    overflow: auto;
    min-width: 0;
  }
  .fx {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    pointer-events: none;
    z-index: 3;
    overflow: visible;
  }

  /* stage */
  .hero {
    margin: 0;
    max-width: 11ch;
    font: 600 46px/1.04 var(--font-ui);
    letter-spacing: -0.035em;
    text-wrap: balance;
  }
  .sub {
    margin: 20px 0 0;
    font-size: 14.5px;
    color: var(--text-2);
  }
  .pick {
    color: var(--text-hi);
    text-decoration: underline;
    text-decoration-color: #4b5156;
    text-underline-offset: 4px;
    text-decoration-thickness: 1px;
  }
  .pick:hover {
    text-decoration-color: var(--accent);
  }
  .hk {
    margin-left: 12px;
    font: 12px var(--font-code);
    color: var(--text-3);
  }
  .fmt {
    margin: 6px 0 0;
    font: 12px var(--font-code);
    color: var(--text-3);
    letter-spacing: 0.02em;
  }
  .anchor {
    height: 1px;
    margin: 52px 0 40px;
  }
  .ro {
    cursor: default;
  }
  .ro-h {
    display: flex;
    align-items: baseline;
    gap: 10px;
    margin-bottom: 10px;
  }
  .ro-n {
    font: 500 14px var(--font-ui);
    overflow-wrap: anywhere;
  }
  .ro-s {
    font: 12px var(--font-code);
    color: var(--text-3);
  }
  .hex {
    display: flex;
    flex-wrap: wrap;
    gap: 4px 18px;
    font: 13px/1.6 var(--font-code);
    color: var(--text-2);
  }
  .off,
  .as {
    color: var(--text-3);
  }
  .mg {
    color: var(--beam-lit);
    background: rgba(164, 183, 241, 0.14);
    border-radius: 3px;
    padding: 0 3px;
    margin: 0 -3px;
  }
  .ro-r {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-top: 10px;
    font: 13px var(--font-code);
    color: var(--accent);
  }
  .bad .mg {
    color: var(--error);
    background: rgba(241, 123, 113, 0.13);
  }
  .bad .ro-r {
    color: var(--error);
    font-family: var(--font-ui);
    font-weight: 500;
  }
  .ro-e {
    margin: 6px 0 0;
    max-width: 44ch;
    color: var(--text-2);
  }

  .shelf-in {
    min-height: 100%;
    display: flex;
    flex-direction: column;
  }

  /* shelf: continue */
  .cont {
    display: block;
    width: 100%;
    padding: 22px 26px 20px;
    border-radius: 12px;
    background: var(--lift);
    cursor: pointer;
    outline: none;
  }
  .cont:hover,
  .cont.sel {
    background: var(--lift-2);
  }
  .c-top {
    display: flex;
    justify-content: space-between;
    gap: 16px;
    font-size: 12.5px;
    color: var(--text-3);
  }
  .c-name {
    margin: 8px 0 3px;
    font: 600 24px/1.15 var(--font-ui);
    letter-spacing: -0.02em;
    overflow-wrap: anywhere;
  }
  .c-id {
    font: 12.5px var(--font-code);
    color: var(--text-3);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .c-at {
    display: flex;
    gap: 18px;
    margin-top: 10px;
    font-size: 12.5px;
    color: var(--text-3);
  }
  .c-at:empty {
    display: none;
  }
  .c-at b {
    font-weight: 500;
    color: var(--text-2);
  }
  .c-foot {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px 22px;
    min-height: 26px;
    margin-top: 22px;
    padding-top: 16px;
    border-top: 1px solid var(--rule);
    font-size: 12.5px;
    color: var(--text-3);
  }
  .c-load {
    color: var(--accent);
  }
  .c-miss {
    color: var(--obf);
  }
  .c-acts {
    margin-left: auto;
    display: flex;
    gap: 2px;
  }
  .c-go {
    margin-left: auto;
    color: var(--text-2);
    opacity: 0;
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .cont.sel .c-go,
  .cont.loading .c-go {
    opacity: 1;
  }

  /* shelf: recent list */
  .rhead {
    display: flex;
    align-items: center;
    gap: 14px;
    margin: 44px 0 6px;
  }
  .rhead h2 {
    margin: 0;
    font: 600 15px var(--font-ui);
    letter-spacing: -0.01em;
  }
  .rhead .n {
    font: 12px var(--font-code);
    color: var(--text-3);
  }
  .filter {
    margin-left: auto;
    display: flex;
    align-items: center;
    gap: 8px;
    width: 220px;
    min-width: 0;
    height: 30px;
    padding: 0 10px;
    border-radius: 7px;
    background: #0c0d0e;
    color: var(--text-3);
    box-shadow: inset 0 0 0 1px var(--rule);
  }
  .filter:focus-within {
    box-shadow: inset 0 0 0 1px var(--beam-shade);
  }
  .filter input {
    flex: 1;
    min-width: 0;
    border: 0;
    outline: none;
    background: transparent;
    font-size: 12.5px;
    color: var(--text-hi);
  }
  .filter input::placeholder {
    color: var(--text-3);
  }
  .row,
  .cols {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 52px 96px 104px;
    grid-template-areas: "main kind n when";
    align-items: center;
    gap: 18px;
    width: calc(100% + 32px);
    margin: 0 -16px;
    padding: 12px 16px;
  }
  .cols {
    padding-top: 6px;
    padding-bottom: 6px;
    font-size: 12px;
    color: var(--text-3);
    border-bottom: 1px solid var(--rule);
    margin-bottom: 6px;
  }
  .cols .r {
    text-align: right;
  }
  .row {
    border-radius: 9px;
    cursor: pointer;
    outline: none;
  }
  .row:hover {
    background: rgba(255, 255, 255, 0.028);
  }
  .row.sel {
    background: var(--lift);
  }
  .r-main {
    grid-area: main;
    min-width: 0;
  }
  .r-name {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 14px;
    font-weight: 500;
    min-width: 0;
  }
  .r-name span {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .r-sub {
    margin-top: 2px;
    font: 12px var(--font-code);
    color: var(--text-3);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .r-kind {
    grid-area: kind;
    font: 500 11px var(--font-code);
    letter-spacing: 0.06em;
    color: var(--text-3);
  }
  .r-n {
    grid-area: n;
    text-align: right;
    font: 500 12.5px var(--font-code);
    color: var(--text-2);
    font-variant-numeric: tabular-nums;
    white-space: nowrap;
  }
  .r-when {
    grid-area: when;
    display: flex;
    justify-content: flex-end;
    align-items: center;
    gap: 2px;
    font-size: 12px;
    color: var(--text-3);
    white-space: nowrap;
  }
  .acts {
    display: none;
    gap: 2px;
  }
  .row:hover .t,
  .row.sel .t,
  .row.missing .t {
    display: none;
  }
  .row:hover .acts,
  .row.sel .acts,
  .row.missing .acts {
    display: flex;
  }
  .ib {
    width: 28px;
    height: 28px;
    display: grid;
    place-items: center;
    border-radius: 6px;
    color: var(--text-2);
  }
  .tb {
    height: 26px;
    padding: 0 9px;
    border-radius: 6px;
    font-size: 12px;
    color: var(--text-2);
  }
  .ib:hover,
  .tb:hover {
    background: var(--lift-2);
    color: var(--text-hi);
  }
  .row.missing .r-name {
    color: var(--text-2);
  }
  .row.missing .r-sub {
    color: var(--obf);
  }
  .row.loading .r-sub {
    color: var(--accent);
  }
  .none {
    padding: 22px 0;
    color: var(--text-3);
  }
  .sfoot {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px 20px;
    margin-top: 26px;
    font-size: 12px;
    color: var(--text-3);
  }
  .keys {
    display: inline-flex;
    align-items: center;
  }
  .keys .key {
    margin-right: 6px;
  }
  .keys :global(svg.ti) {
    margin: -1px 0;
  }
  .clr {
    margin-left: auto;
    color: var(--text-3);
  }
  .clr:hover {
    color: var(--text-hi);
  }

  /* fx */
  .beam {
    stroke-width: 1.6;
    opacity: 0.7;
  }
  .work:has(.stage:hover) .beam,
  .hot .beam {
    opacity: 1;
  }
  .hot .beam {
    stroke-width: 3;
  }
  .ray {
    opacity: 0.8;
  }
  .blocked .prism {
    opacity: 0.3;
  }
  .blocked .beam {
    opacity: 0;
  }

  @media (max-width: 1180px) {
    .row,
    .cols {
      grid-template-columns: minmax(0, 1fr) 84px 96px;
      grid-template-areas: "main n when";
    }
    .r-kind,
    .cols .ck {
      display: none;
    }
    .shelf {
      padding-right: 36px;
    }
  }
</style>
