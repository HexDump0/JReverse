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

<div class="notes">
  {#if !renames.length && !comments.length && !ws.project.bookmarks.length}
    <p class="none">Renames, comments and bookmarks you add show up here and are saved with the file.</p>
    <dl class="keys">
      <dt><span class="key">N</span></dt><dd>Rename what's under the caret</dd>
      <dt><span class="key">;</span></dt><dd>Comment on it</dd>
      <dt><span class="key">Ctrl B</span></dt><dd>Bookmark the line</dd>
    </dl>
  {/if}

  {#if renames.length}
    <div class="sec">Renamed <span class="n">{renames.length}</span></div>
    {#each renames as [id, name] (id)}
      <div class="row">
        <button class="go" onclick={() => onnode(id)} title={dotted(id)}>
          <span class="new">{name}</span><span class="old">{original(id)}</span>
        </button>
        <button class="ib" title="Restore the original name" onclick={() => forget("renames", id)}><Icon name="x" size={13} /></button>
      </div>
    {/each}
  {/if}

  {#if comments.length}
    <div class="sec">Comments <span class="n">{comments.length}</span></div>
    {#each comments as [id, text] (id)}
      <div class="row">
        <button class="go" onclick={() => onnode(id)} title={dotted(id)}>
          <span class="txt">{text}</span><span class="old">{ws.project.renames[id] ?? original(id)}</span>
        </button>
        <button class="ib" title="Remove the comment" onclick={() => forget("comments", id)}><Icon name="x" size={13} /></button>
      </div>
    {/each}
  {/if}

  {#if ws.project.bookmarks.length}
    <div class="sec">Bookmarks <span class="n">{ws.project.bookmarks.length}</span></div>
    {#each ws.project.bookmarks as b (b.cls + ":" + b.line)}
      <div class="row">
        <button class="go" onclick={() => onbookmark(b.cls, b.line)} title={dotted(b.cls)}>
          <span class="txt mono">{b.note || "(empty line)"}</span><span class="old">{ws.className(b.cls)}:{b.line + 1}</span>
        </button>
        <button class="ib" title="Remove the bookmark" onclick={() => ws.toggleBookmark(b.cls, b.line, b.note)}><Icon name="x" size={13} /></button>
      </div>
    {/each}
  {/if}
</div>

<style>
  .notes {
    flex: 1;
    min-height: 0;
    overflow: auto;
    padding-bottom: 12px;
  }
  .none {
    margin: 0;
    padding: 14px 12px 10px;
    color: var(--text-3);
    font-size: 12.5px;
    line-height: 1.5;
  }
  .keys {
    display: grid;
    grid-template-columns: auto 1fr;
    gap: 8px 10px;
    margin: 0;
    padding: 4px 12px;
    font-size: 12px;
    color: var(--text-3);
    align-items: center;
  }
  .keys dd {
    margin: 0;
  }
  .sec {
    padding: 12px 12px 4px;
    font-size: 11.5px;
    font-weight: 600;
    color: var(--text-3);
  }
  .sec .n {
    font: 400 11px var(--font-code);
  }
  .row {
    display: flex;
    align-items: center;
    padding-right: 6px;
  }
  .row:hover {
    background: rgba(255, 255, 255, 0.03);
  }
  .go {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 1px;
    padding: 4px 12px;
    text-align: left;
  }
  .new,
  .txt {
    font-size: 12.5px;
    color: var(--text-hi);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .mono {
    font: 11.5px var(--font-code);
  }
  .old {
    font: 11px var(--font-code);
    color: var(--faint);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .ib {
    flex: none;
    width: 24px;
    height: 24px;
    display: grid;
    place-items: center;
    border-radius: 5px;
    color: var(--text-3);
    opacity: 0;
  }
  .row:hover .ib {
    opacity: 1;
  }
  .ib:hover {
    background: var(--lift-2);
    color: var(--text-hi);
  }
</style>
