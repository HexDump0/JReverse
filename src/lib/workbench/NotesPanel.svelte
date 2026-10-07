<script lang="ts">
  // What you've added to this file: renames, comments and bookmarks. Saved with the file.
  import Icon from "$lib/Icon.svelte";
  import { dotted, originalMember, ownerOf, simpleName } from "./ids";
  import type { Workspace } from "./workspace.svelte";

  interface Props {
    ws: Workspace;
    /** Opens a node's declaration by id. */
    onnode: (id: string) => void;
    onbookmark: (cls: string, line: number) => void;
  }

  let { ws, onnode, onbookmark }: Props = $props();

  const renames = $derived(Object.entries(ws.project.renames).sort((a, b) => a[0].localeCompare(b[0])));
  const comments = $derived(Object.entries(ws.project.comments).sort((a, b) => a[0].localeCompare(b[0])));

  /** `com/foo/Bar.run(I)V` as `Bar.run`, the original names. */
  function original(id: string): string {
    return id.includes(".") ? `${simpleName(ownerOf(id))}.${originalMember(id)}` : simpleName(id);
  }

  function forget(kind: "renames" | "comments", id: string) {
    const node = { id, name: original(id) };
    if (kind === "renames") ws.rename(node, null);
    else ws.comment(node, null);
  }
</script>

<div class="ph">
  <h2>Notes</h2>
  <span class="n">{renames.length + comments.length + ws.project.bookmarks.length}</span>
</div>

<div class="notes">
  {#if !renames.length && !comments.length && !ws.project.bookmarks.length}
    <p class="none">Renames, comments and bookmarks you add show up here and are saved with the file.</p>
    <dl class="keys">
      <dt><span class="key">N</span></dt><dd>Rename what's under the caret</dd>
      <dt><span class="key">;</span></dt><dd>Comment on it</dd>
      <dt><span class="key">Ctrl B</span></dt><dd>Bookmark the line</dd>
    </dl>
  {/if}

  {#if ws.project.bookmarks.length}
    <div class="sec">Bookmarks <span class="n">{ws.project.bookmarks.length}</span></div>
    {#each ws.project.bookmarks as b (b.cls + ":" + b.line)}
      <div class="row">
        <button class="go" onclick={() => onbookmark(b.cls, b.line)} title={dotted(b.cls)}>
          <span class="a">{ws.className(b.cls)}<span class="dim">:{b.line + 1}</span></span>
          <span class="b mono">{b.note || "(empty line)"}</span>
        </button>
        <button class="ib" title="Remove the bookmark" onclick={() => ws.toggleBookmark(b.cls, b.line, b.note)}><Icon name="x" size={14} /></button>
      </div>
    {/each}
  {/if}

  {#if comments.length}
    <div class="sec">Comments <span class="n">{comments.length}</span></div>
    {#each comments as [id, text] (id)}
      <div class="row">
        <button class="go" onclick={() => onnode(id)} title={dotted(id)}>
          <span class="a">{ws.project.renames[id] ?? original(id)}</span>
          <span class="b note">{text}</span>
        </button>
        <button class="ib" title="Remove the comment" onclick={() => forget("comments", id)}><Icon name="x" size={14} /></button>
      </div>
    {/each}
  {/if}

  {#if renames.length}
    <div class="sec">Renames <span class="n">{renames.length}</span></div>
    {#each renames as [id, name] (id)}
      <div class="row">
        <button class="go" onclick={() => onnode(id)} title={dotted(id)}>
          <span class="a">{original(id)} <span class="dim">to</span> {name}</span>
          <span class="b">{id.includes(".") ? (id.includes("(") ? "method" : "field") : "class"} in {simpleName(ownerOf(id))}</span>
        </button>
        <button class="ib" title="Restore the original name" onclick={() => forget("renames", id)}><Icon name="x" size={14} /></button>
      </div>
    {/each}
  {/if}
</div>

<style>
  .notes {
    flex: 1;
    min-height: 0;
    overflow: auto;
    padding: 0 6px 12px;
  }
  .none {
    margin: 0;
    padding: 0 10px 12px;
    color: var(--text-3);
  }
  .keys {
    display: grid;
    grid-template-columns: auto 1fr;
    gap: 10px 12px;
    margin: 4px 10px;
    color: var(--text-2);
  }
  .keys dt {
    text-align: right;
  }
  .keys dd {
    margin: 0;
  }
  .sec {
    padding: 12px 10px 4px;
    font-weight: 600;
    color: var(--text-2);
  }
  .sec .n {
    font-weight: 400;
    color: var(--text-3);
  }
  .row {
    display: flex;
    align-items: center;
    border-radius: 8px;
  }
  .row:hover {
    background: var(--hover);
  }
  .row .ib {
    visibility: hidden;
    margin-right: 4px;
  }
  .row:hover .ib {
    visibility: visible;
  }
  .go {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 1px;
    padding: 6px 10px 7px;
    text-align: left;
  }
  .a {
    font: 13px var(--font-code);
    color: var(--text-hi);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .b {
    color: var(--text-3);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .b.mono {
    font: 12.5px var(--font-code);
  }
  .b.note {
    color: var(--text-2);
    white-space: normal;
  }
  .dim {
    color: var(--text-3);
  }
</style>
