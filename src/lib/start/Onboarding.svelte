<script lang="ts" module>
  const DONE = "jreverse.onboarded";

  /** Whether this user has been through onboarding. */
  export function onboarded(): boolean {
    try {
      return localStorage.getItem(DONE) === "1";
    } catch {
      return false;
    }
  }
</script>

<script lang="ts">
  // First launch only: the mark, one line, one button. The mark's incoming beam
  // is drawn on from the window's left edge, at the same angle, like the start
  // screen's beam into its prism.
  let { onfinish }: { onfinish: () => void } = $props();

  let root: HTMLDivElement;
  let mark: SVGSVGElement;
  let beam = $state({ points: "", x1: 0, y1: 0, x2: 0, y2: 0 });

  // The mark's beam polygon in its viewBox: tip at the left, slanting up into the glass.
  const TIP_TOP = [2.5, 33.9];
  const TIP_BOTTOM = [2.5, 37.1];
  const INTO_GLASS = [25.64, 26.72];

  function layout() {
    const R = mark.getBoundingClientRect();
    const O = root.getBoundingClientRect();
    const s = R.width / 62; // viewBox "1 12 62 36"
    const px = ([x, y]: number[]) => [R.left - O.left + (x - 1) * s, R.top - O.top + (y - 12) * s];
    const [tx, ty] = px(TIP_TOP);
    const [, by] = px(TIP_BOTTOM);
    const [gx, gy] = px(INTO_GLASS);
    const len = Math.hypot(gx - tx, gy - ty);
    const [dx, dy] = [(gx - tx) / len, (gy - ty) / len];
    const reach = tx / dx + 2; // back to just past the window's left edge
    const overlap = 1; // tuck under the mark so no seam shows
    const ax = tx - dx * reach;
    const ay = ty - dy * reach;
    beam = {
      points: `${ax},${ay} ${tx + dx * overlap},${ty + dy * overlap} ${tx + dx * overlap},${by + dy * overlap} ${ax},${ay + (by - ty)}`,
      x1: ax,
      y1: ay,
      x2: tx,
      y2: ty,
    };
  }

  $effect(() => {
    const ro = new ResizeObserver(layout);
    ro.observe(root);
    document.fonts?.ready.then(layout);
    return () => ro.disconnect();
  });

  function finish() {
    try {
      localStorage.setItem(DONE, "1");
    } catch {
      // Onboarding shows again next launch; nothing else depends on it.
    }
    onfinish();
  }

  function onkeydown(e: KeyboardEvent) {
    if (e.key === "Enter") {
      e.preventDefault();
      finish();
    }
  }
</script>

<svelte:window {onkeydown} />

<div class="onb" bind:this={root}>
  <svg class="fx" aria-hidden="true">
    <defs>
      <linearGradient id="onbBeam" gradientUnits="userSpaceOnUse" x1={beam.x1} y1={beam.y1} x2={beam.x2} y2={beam.y2}>
        <stop offset="0" stop-color="#cdd7fa" stop-opacity="0" />
        <stop offset=".55" stop-color="#cdd7fa" stop-opacity=".25" />
        <stop offset="1" stop-color="#cdd7fa" stop-opacity="1" />
      </linearGradient>
    </defs>
    <polygon points={beam.points} fill="url(#onbBeam)" />
  </svg>

  <div class="id">
    <svg bind:this={mark} class="mark" viewBox="1 12 62 36" aria-hidden="true"
      ><polygon points="2.5,33.9 25.64,26.72 25.64,29.92 2.5,37.1" fill="#cdd7fa" /><polygon
        points="38.75,29.2 61.5,19.4 61.5,22.6 38.75,32.4"
        fill="#cdd7fa"
      /><polygon points="38.75,29.2 61.5,25.9 61.5,29.1 38.75,32.4" fill="#a4b7f1" /><polygon
        points="38.75,29.2 61.5,32.4 61.5,35.6 38.75,32.4"
        fill="#6f82cf"
      /><polygon points="38.75,29.2 61.5,38.9 61.5,42.1 38.75,32.4" fill="#4f5f9e" /><polygon
        points="32,13 32,47 12.5,47"
        fill="#cdd7fa"
      /><polygon points="32,13 51.5,47 32,47" fill="#6f82cf" /></svg
    >
    <h1>JReverse</h1>
    <p>A modern Java decompilation tool</p>
  </div>

  <button class="go" onclick={finish}><span class="key">Enter</span>Get started</button>
</div>

<style>
  .onb {
    flex: 1;
    min-height: 0;
    position: relative;
    overflow: hidden;
    display: grid;
    grid-template-rows: 1fr auto;
    justify-items: center;
    padding: 0 24px 72px;
    background: var(--void);
    color: var(--text-hi);
  }
  .fx {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    pointer-events: none;
  }
  .id {
    position: relative;
    align-self: center;
    display: flex;
    flex-direction: column;
    align-items: center;
    margin-top: 40px;
  }
  .mark {
    display: block;
    width: 220px;
    height: auto;
  }
  h1 {
    margin: 34px 0 0;
    font: 600 56px/1 var(--font-ui);
    letter-spacing: -0.025em;
  }
  p {
    margin: 16px 0 0;
    font-size: 17px;
    color: var(--text-2);
  }
  .go {
    position: relative;
    display: flex;
    align-items: center;
    gap: 12px;
    height: 48px;
    padding: 0 24px 0 10px;
    border-radius: 11px;
    background: var(--accent);
    color: #0d1020;
    font: 600 15px var(--font-ui);
  }
  .go:hover {
    background: var(--beam-lit);
  }
  .go:focus-visible {
    outline: 2px solid var(--beam-lit);
    outline-offset: 3px;
  }
  .go .key {
    background: rgba(13, 16, 32, 0.12);
    color: #0d1020;
    box-shadow: inset 0 0 0 1px rgba(13, 16, 32, 0.25);
  }
</style>
