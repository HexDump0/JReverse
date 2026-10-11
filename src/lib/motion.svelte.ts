// Motion: short transitions that answer something you did (a menu opening, a tab
// closing). Never on load, never on a loop. Off in the View menu or when the system
// asks for reduced motion; set on <html> as data-motion so CSS transitions follow it
// too. app.html applies the saved choice before paint.
//
// Only opacity and transform are animated: the GPU runs those, so they stay smooth
// at any refresh rate. Width, height and position would re-layout every frame.
import { cubicOut } from "svelte/easing";
import { fade, fly, scale } from "svelte/transition";

const KEY = "jreverse.motion";

function saved(): boolean {
  try {
    return localStorage.getItem(KEY) !== "off";
  } catch {
    return true;
  }
}

export const motion = $state({ on: saved() });

export function setMotion(on: boolean) {
  motion.on = on;
  if (on) delete document.documentElement.dataset.motion;
  else document.documentElement.dataset.motion = "off";
  try {
    localStorage.setItem(KEY, on ? "on" : "off");
  } catch {
    // Not remembered; it still applies now.
  }
}

const reduced = () => typeof matchMedia === "function" && matchMedia("(prefers-reduced-motion: reduce)").matches;
const ms = (n: number) => (motion.on && !reduced() ? n : 0);

/** Opening is a little slower than closing. */
const IN = 150;
const OUT = 100;

// Svelte passes the direction: use them as `in:pop out:pop`.
type Opts = { direction?: "in" | "out" | "both" };

/** Dialogs and the palette: fade in while growing from 98%. */
export const pop = (node: Element, _?: unknown, { direction }: Opts = {}) => scale(node, { start: 0.98, duration: ms(direction === "out" ? OUT : IN), easing: cubicOut });

/** Menus: drop 4px from the bar or the pointer while fading in. */
export const drop = (node: Element, _?: unknown, { direction }: Opts = {}) => fly(node, { y: -4, duration: ms(direction === "out" ? OUT : IN), easing: cubicOut });

/** Status messages: rise 4px into place. */
export const rise = (node: Element, _?: unknown, { direction }: Opts = {}) => fly(node, { y: 4, duration: ms(direction === "out" ? OUT : IN), easing: cubicOut });

/** Overlays such as the drop target. */
export const fadeIn = (node: Element, _?: unknown, { direction }: Opts = {}) => fade(node, { duration: ms(direction === "out" ? OUT : 120) });

/** Params for `animate:flip`: tabs and rows glide to their new place (a transform). */
export const glide = { get duration() { return ms(200); }, easing: cubicOut };
