<script lang="ts">
  // Everything about the name under the caret, or the member the caret is in:
  // what it is, where it's used (fetched as the caret moves), what it calls,
  // your rename and comment, and a Frida hook to copy.
  import { errorMessage, findUsages, type NodeInfo, type Usage } from "$lib/engine";
  import Icon from "$lib/Icon.svelte";
  import { fmtN } from "$lib/format";
  import { highlight } from "$lib/java/highlight";
  import { enclosing, linkAt, type Doc, type Pos } from "./doc";
  import { fridaSnippet } from "./hooks";
  import { originalMember, ownerOf } from "./ids";
  import Snippet from "./Snippet.svelte";
  import { dotted, simpleName, type Tab, type Workspace } from "./workspace.svelte";

  interface Props {
    ws: Workspace;
    doc: Doc | null;
    tab: Tab;
    onusage: (u: Usage) => void;
    onallusages: (node: NodeInfo) => void;
    onjump: (pos: Pos) => void;
    onrename: () => void;
    oncomment: () => void;
    onbookmark: () => void;
    oncopy: (text: string, what: string) => void;
    onclose: () => void;
  }

  let { ws, doc, tab, onusage, onallusages, onjump, onrename, oncomment, onbookmark, oncopy, onclose }: Props = $props();

  const SHOWN = 40;

  /** The name under the caret if it is one, else the member the caret is in. */
  const node = $derived.by((): NodeInfo | null => {
    if (!doc || tab.kind !== "class") return null;
    const link = linkAt(doc, tab.caret);
    if (link) return doc.nodes[link.node];
    return enclosing(doc, tab.caret.line) ?? null;
  });

  const decl = $derived(node && doc ? doc.decls.get(node.id) : undefined);

  /** The declaration as written, e.g. `public static License verify(String str, String str2)`. */
  const signature = $derived.by(() => {
    if (!node) return "";
    if (decl && doc) return doc.lines[decl.line].trim().replace(/\s*\{\s*$/, "").replace(/;\s*$/, "");
    return node.kind === "class" ? dotted(node.id) : node.detail;
  });

  const owner = $derived(node ? (node.kind === "class" ? dotted(node.id.slice(0, Math.max(0, node.id.lastIndexOf("/")))) : ws.className(ownerOf(node.id))) : "");

  const original = $derived(node ? (node.kind === "class" ? simpleName(node.id) : originalMember(node.id)) : "");

  /** Methods this one calls, read from the decompiled body. */
  const calls = $derived.by((): NodeInfo[] => {
    if (!node || node.kind !== "method" || !decl || !doc) return [];
    const next = doc.declLines.find((d) => d.line > decl.line);
    const end = next ? next.line : doc.lines.length;
    const seen = new Map<string, NodeInfo>();
    for (let l = decl.line; l < end; l++) {
      for (const k of doc.links[l] ?? []) {
        const n = doc.nodes[k.node];
        if (!k.decl && n.kind === "method" && n.id !== node.id && !seen.has(n.id)) seen.set(n.id, n);
      }
    }
    return [...seen.values()];
  });

  /* ---------- usages, fetched as the caret settles ---------- */
  type Found = { state: "loading" } | { state: "ready"; usages: Usage[] } | { state: "error"; message: string };
  const cache = new Map<string, Found>();
  let found = $state<Found | null>(null);
  let timer: ReturnType<typeof setTimeout> | undefined;

  $effect(() => {
    const n = node;
    const key = n ? `${ws.names}:${n.id}` : "";
    clearTimeout(timer);
    if (!n) return void (found = null);
    const hit = cache.get(key);
    if (hit) return void (found = hit);
    found = { state: "loading" };
    timer = setTimeout(async () => {
      try {
        const r = await findUsages(ws.session, n.id);
        cache.set(key, { state: "ready", usages: r.usages });
      } catch (e) {
        cache.set(key, { state: "error", message: errorMessage(e) });
      }
      if (node?.id === n.id) found = cache.get(key)!;
    }, 180);
    return () => clearTimeout(timer);
  });

  const heading = $derived.by(() => {
    if (!node || found?.state !== "ready") return node?.kind === "method" ? "Called from" : "Used in";
    const n = found.usages.length;
    const places = `${fmtN(n)} ${n === 1 ? "place" : "places"}`;
    if (node.kind === "method") return n ? `Called from ${places}` : "Not called in this file";
    if (node.kind === "field") return n ? `Read and written in ${places}` : "Not used in this file";
    return n ? `Used in ${places}` : "Not used in this file";
  });

  const frida = $derived(node ? fridaSnippet(node, decl && doc ? doc.lines[decl.line] : undefined) : "");

  const kindLetter = (n: NodeInfo) => (n.kind === "class" ? "C" : n.kind === "method" ? "m" : "f");
  const kindClass = (n: NodeInfo) => (n.kind === "class" ? "k-c" : n.kind === "method" ? "k-m" : "k-f");
  const what = (n: NodeInfo) => (n.kind === "class" ? "Class in" : n.kind === "method" ? (n.id.includes(".<init>(") ? "Constructor of" : "Method in") : "Field in");
  /** `(int, String)` from a detail like `run(int, String): void`. */
  const params = (n: NodeInfo) => (n.kind === "method" ? (/\(.*\)/.exec(n.detail)?.[0] ?? "()") : "");
</script>

{#snippet code(text: string)}{#each highlight(text) as line, i (i)}{#if i}{"\n"}{/if}{#each line as [k, t], j (j)}<span class="t{k}">{t}</span>{/each}{/each}{/snippet}

<section class="island insp" aria-label="Inspector">
  <div class="ph">
    {#if node}
      <h2 class="name"><span class="k {kindClass(node)}">{kindLetter(node)}</span>{node.name}</h2>
    {:else}
      <h2>Inspector</h2>
    {/if}
    <span class="acts"><button class="ib" title="Hide (Ctrl Alt I)" onclick={onclose}><Icon name="x" size={15} /></button></span>
  </div>

  <div class="body">
    {#if !node}
      <p class="empty">Put the caret on a class, method or field to see what it is and where it's used.</p>
    {:else}
      <div class="sym">
        <div class="what">{what(node)} <b>{owner}</b></div>
        <div class="sig selectable">{@render code(signature)}</div>
        {#if ws.project.renames[node.id]}<div class="was">You renamed this from <code>{original}</code></div>{/if}
      </div>

      <div class="acts-row">
        <button class="act" onclick={onrename} title="Rename (N)"><Icon name="pencil" size={15} />Rename<kbd>N</kbd></button>
        <button class="act" onclick={oncomment} title="Comment (;)"><Icon name="message" size={15} />Comment<kbd>;</kbd></button>
        <button class="act" onclick={onbookmark} title="Bookmark the line (Ctrl B)"><Icon name="bookmark" size={15} /><kbd>Ctrl B</kbd></button>
      </div>

      {#if ws.project.comments[node.id]}
        <div class="note"><Icon name="message" size={15} /><span>{ws.project.comments[node.id]}</span></div>
      {/if}

      <div class="sec">
        <div class="sh">
          <h3>{heading}</h3>
          {#if found?.state === "loading"}<i class="spin"></i>{/if}
          {#if found?.state === "ready" && found.usages.length}
            <button class="more" title="Open the usages panel (X)" onclick={() => onallusages(node)}>Open all</button>
          {/if}
        </div>
        {#if found?.state === "error"}
          <p class="empty err">{found.message}</p>
        {:else if found?.state === "ready"}
          <div class="list">
            {#each found.usages.slice(0, SHOWN) as u (u.cls + ":" + u.line + ":" + u.col)}
              <button class="use" onclick={() => onusage(u)}>
                <span class="where">
                  <span class="k k-c">C</span><b>{ws.className(u.cls)}</b>
                  {#if u.in && u.in.kind !== "class"}<span class="in">{u.in.name}{params(u.in)}</span>{/if}
                  <span class="ln">{u.line + 1}</span>
                </span>
                <span class="snip"><Snippet text={u.text} col={u.col} len={u.len} /></span>
              </button>
            {/each}
            {#if found.usages.length > SHOWN}
              <button class="more-row" onclick={() => onallusages(node)}>{fmtN(found.usages.length - SHOWN)} more in the usages panel</button>
            {/if}
          </div>
        {/if}
      </div>

      {#if calls.length}
        <div class="sec">
          <div class="sh"><h3>Calls</h3><span class="n">{calls.length}</span></div>
          <div class="list">
            {#each calls as c (c.id)}
              {@const pos = doc?.decls.get(c.id)}
              <button class="call" onclick={() => (pos ? onjump(pos) : ws.goToNode(c))} title={dotted(ownerOf(c.id))}>
                <span class="k k-m">m</span><span class="cn">{#if c.id.includes(".<init>(")}<span class="tk">new </span>{ws.className(ownerOf(c.id))}{:else}<span class="dim">{ws.className(ownerOf(c.id))}.</span>{c.name}{/if}<span class="dim">{params(c)}</span></span>
              </button>
            {/each}
          </div>
        </div>
      {/if}

      <div class="sec">
        <div class="sh"><h3>Hook with Frida</h3><button class="more" title="Copy (F)" onclick={() => oncopy(frida, `a Frida snippet for ${node.name}`)}>Copy</button></div>
        <pre class="frida selectable">{@render code(frida)}</pre>
      </div>
    {/if}
  </div>
</section>

<style>
  .insp {
    height: 100%;
  }
  .name {
    display: flex;
    align-items: center;
    gap: 9px;
    min-width: 0;
    font-family: var(--font-code) !important;
    font-weight: 500 !important;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .body {
    flex: 1;
    min-height: 0;
    overflow: auto;
    padding-bottom: 12px;
  }
  .empty {
    margin: 0;
    padding: 4px 16px 16px;
    color: var(--text-3);
  }
  .empty.err {
    color: var(--bad);
  }
  .sym {
    padding: 0 16px 12px;
  }
  .what {
    margin-bottom: 5px;
    color: var(--text-3);
  }
  .what b {
    font-weight: 500;
    color: var(--text-2);
  }
  .sig {
    font: 13px/1.55 var(--font-code);
    color: var(--text-hi);
    word-break: break-word;
  }
  .was {
    margin-top: 8px;
    color: var(--text-2);
  }
  .was code {
    font-size: 12.5px;
    color: var(--text);
  }
  .acts-row {
    display: flex;
    gap: 2px;
    padding: 0 8px 10px;
  }
  .act {
    display: flex;
    align-items: center;
    gap: 7px;
    height: 30px;
    padding: 0 9px;
    border-radius: 8px;
    color: var(--text);
  }
  .act:hover {
    background: var(--hover);
    color: var(--text-hi);
  }
  .note {
    display: flex;
    gap: 10px;
    margin: 0 12px 12px;
    padding: 9px 12px;
    border-radius: 9px;
    background: color-mix(in srgb, var(--ink) 11%, transparent);
    color: var(--text-hi);
    font-weight: 500;
    user-select: text;
  }
  .note :global(svg) {
    margin-top: 2px;
    color: var(--ink);
  }
  .sec {
    padding-top: 6px;
    border-top: 1px solid var(--line);
  }
  .sh {
    height: 36px;
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 0 16px;
  }
  h3 {
    margin: 0;
    font: 600 13.5px/1 var(--font-ui);
    color: var(--text-hi);
  }
  .sh .n {
    color: var(--text-3);
  }
  .more {
    margin-left: auto;
    color: var(--text-3);
    font-size: 12.5px;
  }
  .more:hover {
    color: var(--text);
  }
  .list {
    padding: 0 6px 8px;
  }
  .use {
    display: block;
    width: 100%;
    padding: 7px 10px 8px;
    border-radius: 8px;
    text-align: left;
  }
  .use:hover,
  .call:hover,
  .more-row:hover {
    background: var(--hover);
  }
  .where {
    display: flex;
    align-items: center;
    gap: 8px;
    white-space: nowrap;
    overflow: hidden;
  }
  .where b {
    font-weight: 600;
    color: var(--text-hi);
  }
  .in {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    font-size: 12.5px;
    color: var(--text-3);
  }
  .ln {
    margin-left: auto;
    font: 12px var(--font-code);
    color: var(--text-3);
  }
  .snip {
    display: flex;
    margin-top: 4px;
    padding-left: 25px;
  }
  .more-row {
    width: 100%;
    padding: 6px 10px;
    border-radius: 8px;
    text-align: left;
    color: var(--text-3);
  }
  .call {
    display: flex;
    align-items: center;
    gap: 8px;
    width: 100%;
    padding: 5px 10px;
    border-radius: 7px;
    text-align: left;
    white-space: nowrap;
  }
  .cn {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    font: 12.5px var(--font-code);
    color: var(--text-hi);
  }
  .dim {
    color: var(--text-3);
  }
  .frida {
    margin: 0 12px 4px;
    padding: 12px 14px;
    border-radius: 9px;
    background: var(--editor);
    font: 12px/1.6 var(--font-code);
    color: var(--text);
    white-space: pre;
    overflow: auto;
  }
</style>
