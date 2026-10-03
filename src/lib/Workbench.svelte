<script lang="ts" module>
  import type { ClassEntry, Opened } from "$lib/engine";

  export interface Workspace {
    path: string;
    name: string;
    opened: Opened;
    classes: ClassEntry[];
  }
</script>

<script lang="ts">
  // Engine test view: class list and decompiled source. Deliberately plain; the
  // real shell (design/mockup.html) replaces it.
  import { decompileClass, errorMessage, type ClassKind } from "$lib/engine";
  import Dots from "$lib/Dots.svelte";
  import { fmtN, kindLabel } from "$lib/format";
  import { say, setTask } from "$lib/status.svelte";

  const MAX_ROWS = 500;
  const BADGE: Record<ClassKind, string> = {
    class: "C",
    interface: "I",
    enum: "E",
    annotation: "@",
    record: "R",
  };

  let { ws }: { ws: Workspace } = $props();

  let filter = $state("");
  let selected = $state<string | null>(null);
  let source = $state("");

  // Only the latest decompile request may update the view.
  let latest = 0;

  const matches = $derived.by(() => {
    const q = filter.trim().toLowerCase().replaceAll(".", "/");
    const all = q ? ws.classes.filter((c) => c.id.toLowerCase().includes(q)) : ws.classes;
    return { total: all.length, rows: all.slice(0, MAX_ROWS) };
  });

  export async function show(id: string) {
    const token = ++latest;
    selected = id;
    setTask(`Decompiling ${dotted(id)}`);
    try {
      const out = await decompileClass(ws.opened.session, id);
      if (token !== latest) return;
      source = out.source;
      const warn = out.warnings ? `, ${out.warnings} warning${out.warnings === 1 ? "" : "s"}` : "";
      say(`${out.engine}: decompiled in ${out.ms} ms${warn}`);
    } catch (e) {
      if (token !== latest) return;
      source = "";
      say(`Could not decompile ${dotted(id)}: ${errorMessage(e)}`, true);
    } finally {
      if (token === latest) setTask("");
    }
  }

  export function dotted(id: string) {
    return id.replaceAll("/", ".");
  }
</script>

<div class="bench">
  <aside>
    <div class="file">
      <span class="name" title={ws.path}>{ws.name}</span>
      <span class="meta"><Dots parts={[kindLabel(ws.opened.kind), `${fmtN(ws.opened.classCount)} classes`]} /></span>
    </div>
    <input placeholder="Filter classes" bind:value={filter} spellcheck="false" />
    <ul>
      {#each matches.rows as cls (cls.id)}
        <li>
          <button class:selected={cls.id === selected} onclick={() => show(cls.id)} title={dotted(cls.id)}>
            <span class="badge">{BADGE[cls.kind]}</span>
            <span class="cname">{dotted(cls.id)}</span>
          </button>
        </li>
      {/each}
    </ul>
    {#if matches.total > MAX_ROWS}
      <p class="more">{fmtN(matches.total - MAX_ROWS)} more, refine the filter</p>
    {/if}
  </aside>

  <main>
    {#if source}
      <pre>{source}</pre>
    {:else}
      <p class="empty">Pick a class, or press Ctrl P to go to one.</p>
    {/if}
  </main>
</div>

<style>
  .bench {
    flex: 1;
    min-height: 0;
    display: grid;
    grid-template-columns: minmax(220px, 320px) 1fr;
  }
  aside {
    display: flex;
    flex-direction: column;
    min-height: 0;
    background: var(--side);
    border-right: 1px solid var(--line);
  }
  .file {
    display: flex;
    flex-direction: column;
    gap: 2px;
    padding: 10px 10px 4px;
  }
  .name {
    font-weight: 600;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .meta {
    color: var(--muted);
    font-size: 12px;
  }
  input {
    margin: 8px;
    padding: 4px 8px;
    background: var(--pane);
    border: 1px solid var(--line-2);
    border-radius: 4px;
  }
  input:focus {
    outline: 1px solid var(--accent);
  }
  ul {
    flex: 1;
    margin: 0;
    padding: 0;
    list-style: none;
    overflow: auto;
  }
  li button {
    display: flex;
    align-items: center;
    gap: 6px;
    width: 100%;
    padding: 2px 8px;
    text-align: left;
    white-space: nowrap;
  }
  li button:hover {
    background: var(--curline);
  }
  li button.selected {
    background: var(--sel);
  }
  .badge {
    flex: none;
    width: 16px;
    height: 16px;
    border-radius: 50%;
    font-size: 10px;
    line-height: 16px;
    text-align: center;
    color: var(--pane);
    background: var(--muted);
  }
  .cname {
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .more {
    margin: 0;
    padding: 6px 8px;
    color: var(--faint);
  }
  main {
    min-width: 0;
    overflow: auto;
    background: var(--pane);
  }
  pre {
    margin: 0;
    padding: 12px 16px;
    font-family: var(--font-code);
    font-size: 13px;
    line-height: 1.6;
    tab-size: 4;
  }
  .empty {
    padding: 16px;
    color: var(--faint);
  }
</style>
