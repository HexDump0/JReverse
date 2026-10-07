<script lang="ts">
  // Every keyboard shortcut, on F1. Keys follow jadx-gui where it has one.
  let { onclose }: { onclose: () => void } = $props();

  const GROUPS: { title: string; keys: [string[], string][] }[] = [
    {
      title: "Anywhere",
      keys: [
        [["Ctrl O"], "Open a file"],
        [["Ctrl P"], "Go to a class; @ for a member, : for a line, > for actions"],
        [["Ctrl Shift F"], "Search names, code and strings"],
        [["Ctrl Shift E"], "Filter the class list"],
        [["Ctrl Alt I"], "Show or hide the inspector"],
        [["Alt Left", "Alt Right"], "Back, forward"],
        [["Ctrl W"], "Close the tab"],
        [["Ctrl Tab"], "Next tab"],
        [["Ctrl Shift W"], "Close the file"],
        [["F1"], "This list"],
      ],
    },
    {
      title: "In code",
      keys: [
        [["D", "Ctrl click"], "Go to declaration"],
        [["X"], "Find usages"],
        [["N"], "Rename"],
        [[";"], "Comment"],
        [["F", "Y"], "Copy a Frida or Xposed snippet"],
        [["Tab"], "Java or smali / bytecode"],
        [["Ctrl F", "F3"], "Find in the class, next match"],
        [["Ctrl G"], "Go to line"],
        [["Ctrl B"], "Bookmark the line"],
        [["Esc"], "Back"],
        [["Ctrl S"], "Save the class"],
        [["Ctrl Shift C"], "Copy the whole class"],
        [["Ctrl =", "Ctrl -"], "Text size"],
      ],
    },
  ];

  function onkeydown(e: KeyboardEvent) {
    if (e.key === "Escape" || e.key === "F1") {
      e.preventDefault();
      e.stopImmediatePropagation();
      onclose();
    }
  }
</script>

<svelte:window onkeydowncapture={onkeydown} onmousedown={(e) => !(e.target as Element).closest(".sheet") && onclose()} />

<div class="sheet" role="dialog" aria-label="Keyboard shortcuts">
  <h2>Keyboard shortcuts</h2>
  <div class="cols">
    {#each GROUPS as g (g.title)}
      <section>
        <h3>{g.title}</h3>
        <dl>
          {#each g.keys as [keys, what] (what)}
            <dt>{#each keys as k (k)}<span class="key">{k}</span>{/each}</dt>
            <dd>{what}</dd>
          {/each}
        </dl>
      </section>
    {/each}
  </div>
</div>

<style>
  .sheet {
    position: fixed;
    z-index: 60;
    top: 72px;
    left: 50%;
    transform: translateX(-50%);
    width: min(860px, calc(100% - 32px));
    max-height: calc(100% - 120px);
    overflow: auto;
    padding: 20px 26px 24px;
    background: var(--raised);
    border-radius: 10px;
    box-shadow: var(--shadow);
  }
  h2 {
    margin: 0 0 16px;
    font: 600 15px var(--font-ui);
    color: var(--text-hi);
  }
  .cols {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(340px, 1fr));
    gap: 8px 40px;
  }
  h3 {
    margin: 0 0 10px;
    font: 600 12px var(--font-ui);
    color: var(--text-3);
  }
  dl {
    display: grid;
    grid-template-columns: auto 1fr;
    gap: 9px 16px;
    align-items: center;
    margin: 0;
    font-size: 12.5px;
  }
  dt {
    display: flex;
    gap: 4px;
    justify-content: flex-end;
    white-space: nowrap;
  }
  dd {
    margin: 0;
    color: var(--text-2);
  }
</style>
