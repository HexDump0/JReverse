<script lang="ts">
  // The members of the class in the active tab, in source order. The one the caret is in is marked.
  import type { NodeInfo } from "$lib/engine";
  import { enclosing, type Doc, type Pos } from "./doc";

  let { doc, caret, onjump }: { doc: Doc; caret: Pos; onjump: (pos: Pos) => void } = $props();

  const items = $derived.by(() => {
    const seen = new Set<string>();
    const out: { node: NodeInfo; pos: Pos; depth: number }[] = [];
    for (const d of doc.declLines) {
      const node = doc.nodes[d.node];
      if (seen.has(node.id)) continue;
      seen.add(node.id);
      // Inner classes and their members sit one step in per `$`.
      const owner = node.kind === "class" ? node.id : node.id.slice(0, node.id.indexOf("."));
      const depth = (owner.match(/\$/g)?.length ?? 0) + (node.kind === "class" ? 0 : 1);
      out.push({ node, pos: doc.decls.get(node.id)!, depth });
    }
    return out;
  });

  const here = $derived(enclosing(doc, caret.line)?.id);

  const ACCESS: Record<NodeInfo["access"], string> = { public: "", protected: "protected", private: "private", "": "package" };
</script>

<nav class="outline" aria-label="Outline">
  {#each items as it (it.node.id)}
    <button
      class="it {it.node.kind}"
      class:here={it.node.id === here}
      style:padding-left="{10 + Math.max(0, it.depth - 1) * 12}px"
      onclick={() => onjump(it.pos)}
      title={[ACCESS[it.node.access], it.node.static ? "static" : "", it.node.detail].filter(Boolean).join(" ")}
    >
      <span class="kind">{it.node.kind === "class" ? "C" : it.node.kind === "method" ? "m" : "f"}</span>
      <span class="nm" class:muted={it.node.access === "private"}>{it.node.kind === "class" ? it.node.name : it.node.detail}</span>
      {#if it.node.static && it.node.kind !== "class"}<span class="st">static</span>{/if}
    </button>
  {:else}
    <p class="none">No members.</p>
  {/each}
</nav>

<style>
  .outline {
    flex: 1;
    min-height: 0;
    overflow: auto;
    padding: 4px 0 12px;
  }
  .it {
    display: flex;
    align-items: baseline;
    gap: 7px;
    width: 100%;
    height: 24px;
    padding-right: 10px;
    text-align: left;
    white-space: nowrap;
    font-size: 12px;
  }
  .it:hover {
    background: rgba(255, 255, 255, 0.03);
  }
  .it.here {
    background: var(--sel);
  }
  .it.class {
    margin-top: 4px;
  }
  .kind {
    flex: none;
    width: 10px;
    font: 600 10.5px var(--font-code);
    color: var(--c-method);
  }
  .field .kind {
    color: var(--c-number);
  }
  .class .kind {
    color: var(--c-type);
  }
  .nm {
    overflow: hidden;
    text-overflow: ellipsis;
    font-family: var(--font-code);
    font-size: 11.5px;
    color: var(--text);
  }
  .class .nm {
    font-family: var(--font-ui);
    font-size: 12.5px;
    font-weight: 600;
    color: var(--text-hi);
  }
  .nm.muted {
    color: var(--muted);
  }
  .st {
    font-size: 11px;
    color: var(--faint);
  }
  .none {
    margin: 0;
    padding: 12px;
    color: var(--faint);
  }
</style>
