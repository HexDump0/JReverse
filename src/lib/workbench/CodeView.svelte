<script lang="ts" module>
  export interface Reveal {
    line: number;
    col: number;
    /** Bumped to scroll there again, even to the same place. */
    n: number;
    /** Mark the line until the caret leaves it, e.g. a search hit. */
    mark?: boolean;
  }
</script>

<script lang="ts">
  // Read-only source view. Only the lines on screen are in the DOM, so a
  // 20,000-line class scrolls like a 20-line one. Columns are monospace
  // character cells, which lets the caret and find marks sit at `col * 1ch`.
  import { tick, untrack } from "svelte";
  import Icon from "$lib/Icon.svelte";
  import { linkAt, segments, wordAt, type Doc, type Link, type Pos, type Seg } from "./doc";

  interface Props {
    doc: Doc;
    caret: Pos;
    reveal: Reveal | null;
    fontSize: number;
    /** Ctrl+click, or Enter/D on a link. */
    onfollow: (link: Link, pos: Pos) => void;
    oncaret: (pos: Pos) => void;
    oncontext: (e: MouseEvent, pos: Pos) => void;
    /** Where this view was scrolled to before, in pixels; restored on mount. */
    top?: number;
    /** The reveal already scrolled to; a new one wins over `top`. */
    seen?: number;
    onscrolled?: (top: number) => void;
    onrevealed?: (n: number) => void;
    /** Your comments, one entry per line of text; jadx prints them as `// text` lines. */
    notes?: Set<string>;
    /** Whether a node has a name you gave it. */
    renamed?: (id: string) => boolean;
    /** Bookmarked lines. */
    bookmarks?: number[];
  }

  let {
    doc,
    caret,
    reveal,
    fontSize,
    onfollow,
    oncaret,
    oncontext,
    top: savedTop,
    seen,
    onscrolled,
    onrevealed,
    notes,
    renamed,
    bookmarks = [],
  }: Props = $props();

  const OVERSCAN = 30;
  const PAD = 16;

  let scroller: HTMLDivElement;
  let findInput = $state<HTMLInputElement>();
  let top = $state(0);
  let height = $state(600);
  let marked = $state<number | null>(null);

  const lh = $derived(Math.round(fontSize * 1.7));
  const digits = $derived(String(doc.lines.length).length);
  // A hex dump carries its own offsets; line numbers would only repeat them.
  const numbered = $derived(doc.engine !== "hex");
  const first = $derived(Math.max(0, Math.floor(top / lh) - OVERSCAN));
  const last = $derived(Math.min(doc.lines.length, Math.ceil((top + height) / lh) + OVERSCAN));
  const visible = $derived(Array.from({ length: Math.max(0, last - first) }, (_, i) => first + i));

  // Segments are cached per doc; the map is rebuilt when the doc changes.
  const segCache = $derived.by(() => {
    void doc;
    return new Map<number, Seg[]>();
  });
  function segs(line: number): Seg[] {
    let s = segCache.get(line);
    if (!s) {
      s = segments(doc.tokens[line] ?? [], doc.links[line] ?? []);
      segCache.set(line, s);
    }
    return s;
  }

  // What's under the caret decides what lights up: every link to the same node,
  // or else every identical identifier.
  const focus = $derived.by(() => {
    const link = linkAt(doc, caret);
    if (link) return { node: doc.nodes[link.node].id, word: "" };
    const w = wordAt(doc.lines[caret.line] ?? "", caret.col);
    const word = w ? doc.lines[caret.line].slice(w.start, w.end) : "";
    return { node: "", word: /^[A-Za-z_$][\w$]*$/.test(word) ? word : "" };
  });

  function isOcc(s: Seg): boolean {
    if (focus.node) return !!s.link && doc.nodes[s.link.node].id === focus.node;
    return !!focus.word && s.text === focus.word && s.kind !== "c" && s.kind !== "s" && s.kind !== "k";
  }

  /** Lines that are one of your comments, with whether the line above and below are too. */
  const noteLines = $derived.by(() => {
    const out = new Map<number, { text: string; indent: number }>();
    if (!notes?.size) return out;
    doc.lines.forEach((l, i) => {
      const m = /^(\s*)\/\/ ?(.*)$/.exec(l);
      if (m && notes.has(m[2].trim())) out.set(i, { text: m[2], indent: m[1].length });
    });
    return out;
  });
  const bookmarkSet = $derived(new Set(bookmarks));

  // A faint band behind the member the caret is in: from its declaration to the next one.
  const band = $derived.by(() => {
    let from = -1;
    let to = -1;
    for (const d of doc.declLines) {
      if (d.line <= caret.line) {
        from = doc.nodes[d.node].kind === "class" ? -1 : d.line;
        to = -1;
      } else {
        to = d.line - 1;
        break;
      }
    }
    if (from < 0) return null;
    if (to < 0) to = doc.lines.length - 1;
    // Stop at the member's closing brace, not at the blank lines before the next one.
    while (to > from && !doc.lines[to].trim()) to--;
    return { from, to };
  });

  const warnLines = $derived(new Set(doc.lines.flatMap((l, i) => (l.includes("JADX WARN") || l.includes("JADX ERROR") ? [i] : []))));

  /* ---------- marks beside the scrollbar ---------- */
  type MarkKind = "occ" | "find" | "note" | "bm" | "warn";
  // Where the selected name, find matches, your notes and bookmarks are in the whole class.
  const marks = $derived.by(() => {
    const n = doc.lines.length;
    if (n < 2) return [];
    const out = new Map<string, { kind: MarkKind; line: number }>();
    // One mark per kind and pixel band, so a 20,000-line class doesn't draw 20,000 marks.
    const add = (kind: MarkKind, line: number) => {
      const key = `${kind}:${Math.round((line / n) * 400)}`;
      if (!out.has(key)) out.set(key, { kind, line });
    };
    for (const l of warnLines) add("warn", l);
    for (const l of bookmarks) add("bm", l);
    for (const l of noteLines.keys()) add("note", l);
    if (findOpen) for (const m of matches) add("find", m.line);
    else if (focus.node) {
      doc.links.forEach((ls, i) => {
        if (ls.some((k) => doc.nodes[k.node].id === focus.node)) add("occ", i);
      });
    } else if (focus.word && n < 40000) {
      const re = new RegExp(`(^|[^\\w$])${focus.word.replace(/\$/g, "\\$")}(?![\\w$])`);
      doc.lines.forEach((t, i) => re.test(t) && add("occ", i));
    }
    return [...out.values()];
  });

  function onMarksClick(e: MouseEvent) {
    const r = (e.currentTarget as HTMLElement).getBoundingClientRect();
    const line = Math.floor(((e.clientY - r.top) / r.height) * doc.lines.length);
    move(line, caret.col);
    scrollToLine(line, 0, false);
    scroller.focus();
  }

  /* ---------- find in file ---------- */
  let findOpen = $state(false);
  let findQuery = $state("");
  let findAt = $state(0);
  const matches = $derived.by(() => {
    const q = findQuery.toLowerCase();
    if (!findOpen || !q) return [];
    const out: { line: number; col: number }[] = [];
    doc.lines.forEach((text, line) => {
      const t = text.toLowerCase();
      for (let i = t.indexOf(q); i >= 0 && out.length < 10000; i = t.indexOf(q, i + q.length)) out.push({ line, col: i });
    });
    return out;
  });
  const visibleMatches = $derived(matches.map((m, i) => ({ ...m, i })).filter((m) => m.line >= first && m.line < last));

  export async function openFind() {
    const sel = window.getSelection()?.toString();
    if (sel && !sel.includes("\n")) findQuery = sel;
    else if (focus.word || focus.node) {
      const w = wordAt(doc.lines[caret.line] ?? "", caret.col);
      if (w) findQuery = doc.lines[caret.line].slice(w.start, w.end);
    }
    findOpen = true;
    await tick();
    findInput?.select();
    jumpToMatch(nearestMatch());
  }

  function nearestMatch(): number {
    const i = matches.findIndex((m) => m.line > caret.line || (m.line === caret.line && m.col >= caret.col));
    return i < 0 ? 0 : i;
  }

  function jumpToMatch(i: number) {
    if (!matches.length) return;
    findAt = (i + matches.length) % matches.length;
    const m = matches[findAt];
    oncaret({ line: m.line, col: m.col });
    scrollToLine(m.line, m.col, true);
  }

  function closeFind() {
    findOpen = false;
    scroller.focus();
  }

  function onFindKey(e: KeyboardEvent) {
    if (e.key === "Enter") {
      e.preventDefault();
      jumpToMatch(findAt + (e.shiftKey ? -1 : 1));
    } else if (e.key === "Escape") {
      e.preventDefault();
      e.stopPropagation();
      closeFind();
    }
  }

  /* ---------- scrolling ---------- */
  function scrollToLine(line: number, col: number, onlyIfHidden: boolean) {
    if (!scroller) return;
    const y = line * lh;
    const inView = y >= scroller.scrollTop + lh && y + lh <= scroller.scrollTop + scroller.clientHeight - lh;
    if (!onlyIfHidden || !inView) scroller.scrollTop = Math.max(0, y - scroller.clientHeight / 3);
    // Bring the column into view too, for long lines.
    const ch = fontSize * 0.6;
    const x = gutterPx() + PAD + col * ch;
    if (x < scroller.scrollLeft + gutterPx() + PAD || x > scroller.scrollLeft + scroller.clientWidth - 40) {
      scroller.scrollLeft = Math.max(0, x - scroller.clientWidth / 2);
    }
  }

  const gutterPx = () => (numbered ? (digits + 4) * fontSize * 0.6 : 0);

  $effect(() => {
    const r = reveal;
    if (!r || r.n === seen) return;
    void doc;
    tick().then(() => {
      scrollToLine(r.line, r.col, false);
      marked = r.mark ? r.line : null;
      onrevealed?.(r.n);
    });
  });

  // Back to where this tab was, unless a jump is waiting.
  $effect(() => {
    untrack(() => {
      if (savedTop !== undefined && (!reveal || reveal.n === seen)) tick().then(() => (scroller.scrollTop = savedTop!));
    });
  });

  $effect(() => {
    if (marked !== null && caret.line !== marked) marked = null;
  });

  $effect(() => {
    const ro = new ResizeObserver(() => (height = scroller.clientHeight));
    ro.observe(scroller);
    return () => ro.disconnect();
  });

  function onscroll() {
    top = scroller.scrollTop;
    onscrolled?.(top);
  }

  /* ---------- caret ---------- */
  function posFromPoint(e: MouseEvent): Pos | null {
    const lineEl = (e.target as Element).closest<HTMLElement>("[data-l]");
    if (!lineEl) return null;
    const line = Number(lineEl.dataset.l);
    let node: Node | null = null;
    let offset = 0;
    const d = document as Document & {
      caretPositionFromPoint?: (x: number, y: number) => { offsetNode: Node; offset: number } | null;
    };
    if (d.caretPositionFromPoint) {
      const p = d.caretPositionFromPoint(e.clientX, e.clientY);
      if (p) [node, offset] = [p.offsetNode, p.offset];
    } else if (document.caretRangeFromPoint) {
      const r = document.caretRangeFromPoint(e.clientX, e.clientY);
      if (r) [node, offset] = [r.startContainer, r.startOffset];
    }
    const seg = (node?.nodeType === Node.TEXT_NODE ? node.parentElement : (node as Element | null))?.closest<HTMLElement>("[data-c]");
    if (seg && lineEl.contains(seg)) return { line, col: Number(seg.dataset.c) + offset };
    return { line, col: (e.target as Element).closest(".no") ? 0 : (doc.lines[line]?.length ?? 0) };
  }

  function onclick(e: MouseEvent) {
    if (window.getSelection()?.toString()) return;
    const pos = posFromPoint(e);
    if (!pos) return;
    oncaret(pos);
    const link = (e.ctrlKey || e.metaKey) && linkAt(doc, pos);
    if (link) onfollow(link, pos);
  }

  function oncontextmenu(e: MouseEvent) {
    const pos = posFromPoint(e);
    if (!pos) return;
    e.preventDefault();
    oncaret(pos);
    oncontext(e, pos);
  }

  function clampCol(line: number, col: number) {
    return Math.max(0, Math.min(col, doc.lines[line]?.length ?? 0));
  }

  function move(line: number, col: number) {
    line = Math.max(0, Math.min(doc.lines.length - 1, line));
    const pos = { line, col: clampCol(line, col) };
    oncaret(pos);
    scrollToLine(pos.line, pos.col, true);
  }

  function wordJump(dir: 1 | -1) {
    const text = doc.lines[caret.line] ?? "";
    let c = caret.col;
    if (dir > 0) {
      while (c < text.length && /[\w$]/.test(text[c])) c++;
      while (c < text.length && !/[\w$]/.test(text[c])) c++;
    } else {
      while (c > 0 && !/[\w$]/.test(text[c - 1])) c--;
      while (c > 0 && /[\w$]/.test(text[c - 1])) c--;
    }
    move(caret.line, c);
  }

  function onkeydown(e: KeyboardEvent) {
    const mod = e.ctrlKey || e.metaKey;
    const page = Math.max(1, Math.floor(scroller.clientHeight / lh) - 2);
    switch (e.key) {
      case "ArrowUp":
        move(caret.line - 1, caret.col);
        break;
      case "ArrowDown":
        move(caret.line + 1, caret.col);
        break;
      case "ArrowLeft":
        if (e.altKey) return;
        if (mod) wordJump(-1);
        else move(caret.line, caret.col - 1);
        break;
      case "ArrowRight":
        if (e.altKey) return;
        if (mod) wordJump(1);
        else move(caret.line, caret.col + 1);
        break;
      case "PageUp":
        move(caret.line - page, caret.col);
        break;
      case "PageDown":
        move(caret.line + page, caret.col);
        break;
      case "Home":
        if (mod) move(0, 0);
        else {
          const indent = (doc.lines[caret.line] ?? "").search(/\S|$/);
          move(caret.line, caret.col === indent ? 0 : indent);
        }
        break;
      case "End":
        if (mod) move(doc.lines.length - 1, 0);
        else move(caret.line, Infinity);
        break;
      case "f":
        if (!mod || e.shiftKey) return;
        openFind();
        break;
      case "a": {
        // Only the lines on screen exist in the DOM, so select those; Copy class source takes it all.
        if (!mod) return;
        const range = document.createRange();
        range.selectNodeContents(scroller.querySelector(".sizer")!);
        const sel = window.getSelection();
        sel?.removeAllRanges();
        sel?.addRange(range);
        break;
      }
      case "F3":
        if (!findOpen) openFind();
        else jumpToMatch(findAt + (e.shiftKey ? -1 : 1));
        break;
      case "Escape":
        if (!findOpen) return;
        closeFind();
        break;
      default:
        return;
    }
    e.preventDefault();
    e.stopPropagation();
  }

  export function focusCode() {
    scroller?.focus();
  }

  /** Lines currently on screen, for remembering a tab's place. */
  export function topLine(): number {
    return Math.floor((scroller?.scrollTop ?? 0) / lh);
  }
</script>

<div class="wrap">
  <!-- svelte-ignore a11y_no_noninteractive_tabindex -->
  <div
    class="code selectable"
    role="textbox"
    aria-readonly="true"
    aria-multiline="true"
    aria-label="Source"
    tabindex="0"
    bind:this={scroller}
    {onscroll}
    {onclick}
    {oncontextmenu}
    {onkeydown}
    style:--fs="{fontSize}px"
    style:--lh="{lh}px"
    style:--gw={numbered ? `${digits + 4}ch` : "0px"}
  >
    <div class="sizer" style:height="{doc.lines.length * lh}px" style:width="calc(var(--gw) + {doc.width + 4}ch + {PAD * 2}px)">
      {#each visible as i (i)}
        {@const note = noteLines.get(i)}
        <div
          class="ln"
          class:cur={i === caret.line}
          class:band={band !== null && i >= band.from && i <= band.to}
          class:warn={warnLines.has(i)}
          class:marked={i === marked}
          style:top="{i * lh}px"
          data-l={i}
        >
          {#if numbered}<span class="no"
              >{#if bookmarkSet.has(i)}<span class="bm" title="Bookmark"><Icon name="bookmark" size={12} /></span>{/if}{i + 1}</span
            >{/if}{#if note}<span class="tx"
              ><span
                class="note"
                class:joined-up={noteLines.has(i - 1)}
                class:joined-down={noteLines.has(i + 1)}
                style:margin-left="{note.indent}ch"
                title="Your comment">{#if !noteLines.has(i - 1)}<Icon name="message" size={14} />{:else}<i class="nsp"></i>{/if}{note.text}</span
              ></span
            >{:else}<span class="tx"
              >{#each segs(i) as s (s.col)}<span
                  class="t{s.kind}"
                  class:lk={!!s.link}
                  class:decl={s.link?.decl}
                  class:occ={isOcc(s)}
                  class:mine={!!s.link && !!renamed?.(doc.nodes[s.link.node].id)}
                  data-c={s.col}>{s.text}</span
                >{/each}</span
            >{/if}
        </div>
      {/each}
      {#each visibleMatches as m (m.i)}
        <div
          class="mark"
          class:on={m.i === findAt}
          style:top="{m.line * lh}px"
          style:left="calc(var(--gw) + {PAD}px + {m.col}ch)"
          style:width="{findQuery.length}ch"
        ></div>
      {/each}
      <div class="caret" style:top="{caret.line * lh}px" style:left="calc(var(--gw) + {PAD}px + {caret.col}ch)"></div>
    </div>
  </div>

  {#if marks.length || doc.lines.length > 1}
    <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
    <div class="marks" onclick={onMarksClick} title="Where the selected name, find matches, your notes and bookmarks are">
      {#each marks as m (m.kind + m.line)}
        <i class="m-{m.kind}" style:top="calc({((m.line + 0.5) / doc.lines.length) * 100}% - 1px)"></i>
      {/each}
      <i class="m-cur" style:top="calc({((caret.line + 0.5) / doc.lines.length) * 100}% - 1px)"></i>
    </div>
  {/if}

  {#if findOpen}
    <div class="find" role="search">
      <Icon name="search" size={14} />
      <input
        bind:this={findInput}
        bind:value={findQuery}
        oninput={() => jumpToMatch(nearestMatch())}
        onkeydown={onFindKey}
        placeholder="Find in class"
        spellcheck="false"
        autocomplete="off"
        aria-label="Find in class"
      />
      <span class="count">{findQuery ? (matches.length ? `${findAt + 1} of ${matches.length}` : "No results") : ""}</span>
      <button title="Previous (Shift Enter)" onclick={() => jumpToMatch(findAt - 1)} disabled={!matches.length}><Icon name="arrowUp" size={14} /></button>
      <button title="Next (Enter)" onclick={() => jumpToMatch(findAt + 1)} disabled={!matches.length}><Icon name="arrowDown" size={14} /></button>
      <button title="Close (Esc)" onclick={closeFind}><Icon name="x" size={14} /></button>
    </div>
  {/if}
</div>

<style>
  .wrap {
    position: relative;
    flex: 1;
    min-width: 0;
    min-height: 0;
    display: flex;
  }
  .code {
    flex: 1;
    min-width: 0;
    overflow: auto;
    background: var(--editor);
    font: var(--fs) / var(--lh) var(--font-code);
    color: var(--text);
    outline: none;
    cursor: text;
    padding-top: 6px;
  }
  .sizer {
    position: relative;
    min-width: 100%;
  }
  .ln {
    position: absolute;
    left: 0;
    right: 0;
    height: var(--lh);
    display: flex;
    white-space: pre;
  }
  .ln.band {
    background: var(--band);
  }
  .ln.cur {
    background: var(--curline);
  }
  .ln.warn .tx {
    background: var(--c-warn-line);
  }
  .ln.marked {
    background: var(--sel);
  }
  .no {
    position: sticky;
    left: 0;
    z-index: 1;
    flex: none;
    width: var(--gw);
    padding-right: 2ch;
    text-align: right;
    color: var(--gutter);
    background: var(--editor);
    font-size: 0.93em;
    user-select: none;
    cursor: default;
  }
  .ln.band .no {
    background: var(--band);
  }
  .ln.cur .no {
    color: var(--text-2);
    background: var(--curline);
  }
  .bm {
    position: absolute;
    left: 6px;
    top: 50%;
    transform: translateY(-50%);
    display: grid;
    color: var(--ink);
  }
  .tx {
    padding-left: 16px;
    flex: 1;
  }
  .lk {
    cursor: pointer;
  }
  .lk:hover {
    text-decoration: underline;
    text-decoration-color: color-mix(in srgb, currentColor 45%, transparent);
    text-underline-offset: 3px;
  }
  .mine {
    text-decoration: underline dotted var(--ink);
    text-underline-offset: 4px;
  }
  .occ {
    background: var(--occ);
    border-radius: 3px;
  }
  /* One of your comments: shown as a note, not as a jadx comment. */
  .note {
    display: inline-flex;
    align-items: center;
    gap: 9px;
    height: calc(var(--lh) - 2px);
    margin-top: 1px;
    padding: 0 12px 0 10px;
    border-radius: 8px;
    background: color-mix(in srgb, var(--ink) 12%, transparent);
    font: 500 13.5px var(--font-ui);
    color: var(--text-hi);
    user-select: text;
  }
  .note :global(svg),
  .nsp {
    display: inline-block;
    color: var(--ink);
    width: 14px;
    flex: none;
  }
  .note.joined-down {
    border-bottom-left-radius: 0;
    border-bottom-right-radius: 0;
    height: var(--lh);
    margin-bottom: -1px;
  }
  .note.joined-up {
    border-top-left-radius: 0;
    border-top-right-radius: 0;
    margin-top: 0;
  }
  .mark {
    position: absolute;
    height: var(--lh);
    background: var(--find);
    border-radius: 3px;
    pointer-events: none;
  }
  .mark.on {
    background: var(--find-on);
  }
  .caret {
    position: absolute;
    width: 2px;
    height: var(--lh);
    margin-left: -1px;
    background: var(--text-hi);
    pointer-events: none;
    opacity: 0;
  }
  .code:focus .caret {
    opacity: 1;
  }

  .marks {
    position: relative;
    width: 14px;
    flex: none;
    background: var(--editor);
    cursor: pointer;
  }
  .marks i {
    position: absolute;
    left: 3px;
    right: 3px;
    height: 3px;
    border-radius: 2px;
    pointer-events: none;
  }
  .m-occ {
    background: var(--accent);
  }
  .m-find {
    background: var(--warn);
  }
  .m-note,
  .m-bm {
    background: var(--ink);
  }
  .m-warn {
    background: var(--bad);
    opacity: 0.7;
  }
  .marks .m-cur {
    left: 1px;
    right: 1px;
    height: 2px;
    background: var(--text-hi);
  }

  .find {
    position: absolute;
    top: 10px;
    right: 26px;
    z-index: 5;
    display: flex;
    align-items: center;
    gap: 4px;
    height: 38px;
    padding: 0 5px 0 12px;
    border-radius: 10px;
    background: var(--raised);
    color: var(--text-3);
    box-shadow: var(--shadow);
  }
  .find input {
    width: 220px;
    border: 0;
    outline: none;
    background: transparent;
    color: var(--text-hi);
    padding: 0 4px;
  }
  .find input::placeholder {
    color: var(--text-3);
  }
  .count {
    min-width: 64px;
    font-size: 12.5px;
    color: var(--text-3);
    white-space: nowrap;
  }
  .find button {
    width: 28px;
    height: 28px;
    display: grid;
    place-items: center;
    border-radius: 7px;
    color: var(--text-2);
  }
  .find button:hover:not(:disabled) {
    background: var(--hover);
    color: var(--text-hi);
  }
  .find button:disabled {
    color: var(--text-3);
    opacity: 0.5;
    cursor: default;
  }
</style>
