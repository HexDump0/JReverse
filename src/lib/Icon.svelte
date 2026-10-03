<script lang="ts">
  import { ICONS, type IconName } from "./icons";

  let { name, size = 16 }: { name: IconName; size?: number } = $props();
</script>

<svg class="ti" width={size} height={size} viewBox="0 0 24 24" aria-hidden="true">{@html ICONS[name]}</svg>
