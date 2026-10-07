<script lang="ts">
  import type { EngineStatus } from "$lib/engine";
  import Icon from "$lib/Icon.svelte";
  import { status } from "$lib/status.svelte";

  let {
    engine,
    info = "",
    where = [],
    inspector = null,
    logOpen,
    ontogglelog,
    ontoggleinspector,
  }: {
    engine: EngineStatus | null;
    /** What's open, e.g. "APK, 4,812 classes". */
    info?: string;
    /** e.g. "Ln 12, Col 5", "jadx, 84 ms". */
    where?: string[];
    /** Whether the inspector is showing; null when there's none to show. */
    inspector?: boolean | null;
    logOpen: boolean;
    ontogglelog: () => void;
    ontoggleinspector?: () => void;
  } = $props();

  const state = $derived.by(() => {
    switch (engine?.state) {
      case "ready":
        return { text: "Engine ready", tone: "ok" };
      case "starting":
        return { text: "Starting the engine", tone: "busy" };
      case "crashed":
        return { text: "Engine stopped", tone: "bad" };
      case "failed":
        return { text: "Engine failed", tone: "bad" };
      case "stopped":
        return { text: "Engine stopped", tone: "idle" };
      default:
        return { text: "Engine starts on first use", tone: "idle" };
    }
  });
</script>

<footer class="statusbar">
  <span class="it eng" title={engine?.state === "ready" ? `${engine.engines.join(", ")}, engine ${engine.version}` : ""}>
    {#if state.tone === "busy"}<i class="spin"></i>{:else}<i class="dot {state.tone}"></i>{/if}{state.text}
  </span>
  {#if info}<span class="it">{info}</span>{/if}
  <span class="msg" class:err={status.error}>{status.message}</span>
  {#if status.task}
    <span class="it task"><i class="spin"></i><span>{status.task}</span></span>
  {/if}
  {#each where as part (part)}<span class="it where">{part}</span>{/each}
  {#if inspector !== null && ontoggleinspector}
    <button class="it sbtn" class:on={inspector} onclick={ontoggleinspector} title="Show or hide the inspector (Ctrl Alt I)"
      ><Icon name="layoutSidebarRight" size={14} />Inspector</button
    >
  {/if}
  <button class="it sbtn" class:on={logOpen} onclick={ontogglelog}><Icon name="terminal" size={14} />Log</button>
</footer>

<style>
  .statusbar {
    height: 30px;
    flex: none;
    display: flex;
    align-items: center;
    gap: 2px;
    padding: 0 8px 0 10px;
    background: var(--frame);
    font-size: 12.5px;
    color: var(--text-3);
    white-space: nowrap;
  }
  .it {
    display: flex;
    align-items: center;
    gap: 7px;
    height: 24px;
    padding: 0 8px;
    border-radius: 6px;
    flex: none;
  }
  .dot {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: var(--text-3);
  }
  .dot.ok {
    background: var(--ok);
  }
  .dot.bad {
    background: var(--bad);
  }
  .msg {
    flex: 1;
    min-width: 0;
    padding: 0 8px;
    overflow: hidden;
    text-overflow: ellipsis;
    color: var(--text-2);
  }
  .msg.err {
    color: var(--bad);
  }
  .task {
    color: var(--text-2);
  }
  .where {
    font-variant-numeric: tabular-nums;
  }
  .sbtn:hover {
    background: var(--hover);
    color: var(--text);
  }
  .sbtn.on {
    color: var(--text-2);
  }
</style>
