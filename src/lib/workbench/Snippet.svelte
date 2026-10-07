<script lang="ts">
  // One line of code, highlighted, with a range picked out and the indent trimmed:
  // a search hit or a usage. `find` marks the range like a find match; otherwise
  // it's the accent, for "this is the thing you asked about".
  import { highlight } from "$lib/java/highlight";
  import { segments } from "./doc";

  let { text, col, len, find = false }: { text: string; col: number; len: number; find?: boolean } = $props();

  const MAX = 160;

  const parts = $derived.by(() => {
    const indent = text.search(/\S|$/);
    let start = indent;
    // Keep the hit on screen when the line is long.
    if (col - start > MAX / 2) start = col - 40;
    const end = Math.min(text.length, start + MAX);
    const out: { text: string; kind: string; hit: boolean }[] = [];
    for (const s of segments(highlight(text)[0] ?? [], len ? [{ col, len, node: 0, decl: false }] : [])) {
      const a = Math.max(s.col, start);
      const b = Math.min(s.col + s.text.length, end);
      if (b > a) out.push({ text: s.text.slice(a - s.col, b - s.col), kind: s.kind, hit: !!s.link });
    }
    return out;
  });
</script>

<span class="snip">{#each parts as p, i (i)}<span class="t{p.kind}" class:hit={p.hit} class:find>{p.text}</span>{/each}</span>

<style>
  .snip {
    font: 12.5px var(--font-code);
    color: var(--text);
    white-space: pre;
    overflow: hidden;
    text-overflow: ellipsis;
    min-width: 0;
  }
  .hit {
    color: var(--accent);
    font-weight: 500;
  }
  .hit.find {
    color: var(--text-hi);
    background: var(--find);
    border-radius: 3px;
    font-weight: 400;
  }
</style>
