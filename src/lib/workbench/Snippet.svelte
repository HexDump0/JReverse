<script lang="ts">
  // One line of code with a range picked out, trimmed of its indent: a search hit or a usage.
  let { text, col, len }: { text: string; col: number; len: number } = $props();

  const MAX = 160;

  const parts = $derived.by(() => {
    const indent = text.search(/\S|$/);
    let start = indent;
    // Keep the hit on screen when the line is long.
    if (col - start > MAX / 2) start = col - 40;
    const end = Math.min(text.length, start + MAX);
    return {
      before: text.slice(start, col),
      hit: text.slice(col, col + len),
      after: text.slice(col + len, end),
    };
  });
</script>

<span class="snip">{parts.before}<mark>{parts.hit}</mark>{parts.after}</span>

<style>
  .snip {
    font: 12px var(--font-code);
    color: var(--text-2);
    white-space: pre;
    overflow: hidden;
    text-overflow: ellipsis;
    min-width: 0;
  }
  mark {
    color: var(--text-hi);
    background: var(--find);
    border-radius: 2px;
  }
</style>
