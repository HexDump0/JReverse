<script lang="ts">
  // Joins parts with the small separator dot; empty parts are skipped.
  let { parts }: { parts: (string | number | false | null | undefined)[] } = $props();

  const shown = $derived(parts.filter((p) => p !== false && p !== null && p !== undefined && p !== ""));
</script>

{#each shown as part, i}{#if i}<i class="sd"></i>{/if}{part}{/each}
