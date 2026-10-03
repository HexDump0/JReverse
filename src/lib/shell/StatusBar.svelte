<script lang="ts">
  import type { Snippet } from "svelte";
  import { status } from "$lib/status.svelte";

  let { logOpen, ontogglelog, children }: { logOpen: boolean; ontogglelog: () => void; children?: Snippet } = $props();
</script>

<footer class="statusbar">
  <span class="msg" class:err={status.error}>{status.message}</span>
  <span class="r">
    {@render children?.()}
    {#each status.where as part (part)}<span class="where">{part}</span>{/each}
    {#if status.task}
      <span class="task"><i class="spin"></i><span>{status.task}</span></span>
    {/if}
    <button class="sbtn" class:on={logOpen} onclick={ontogglelog}>Log</button>
  </span>
</footer>

<style>
  .statusbar {
    height: 28px;
    flex: none;
    display: flex;
    align-items: center;
    gap: 20px;
    padding: 0 8px 0 14px;
    background: var(--ground);
    border-top: 1px solid var(--line);
    font-size: 11.5px;
    color: var(--faint);
    white-space: nowrap;
  }
  .msg {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    color: var(--muted);
  }
  .msg.err {
    color: var(--error);
  }
  .r {
    display: flex;
    align-items: center;
    gap: 4px;
    margin-left: auto;
  }
  .where {
    padding: 0 8px;
    color: var(--faint);
    font-variant-numeric: tabular-nums;
  }
  .task {
    display: flex;
    align-items: center;
    gap: 7px;
    padding: 0 8px;
    color: var(--muted);
  }
  .sbtn {
    height: 22px;
    display: flex;
    align-items: center;
    padding: 0 8px;
    border-radius: 3px;
    color: var(--faint);
  }
  .sbtn:hover {
    color: var(--text);
    background: #25282a;
  }
  .sbtn.on {
    color: var(--accent);
  }
</style>
