import type { InputKind } from "./engine";

export const fmtN = (n: number) => n.toLocaleString("en-US");

export function fmtSize(b: number): string {
  if (b < 1024) return `${b} B`;
  if (b < 1048576) return `${Math.round(b / 1024)} KB`;
  return `${(b / 1048576).toFixed(1)} MB`;
}

const KIND_LABEL: Record<InputKind, string> = { apk: "APK", aar: "AAR", jar: "JAR", dex: "DEX", class: "Class file" };
export const kindLabel = (k: InputKind) => KIND_LABEL[k] ?? k.toUpperCase();

const DAY = 86_400_000;
const startOfDay = (t: number) => new Date(t).setHours(0, 0, 0, 0);

/** "Just now", "12 min ago", "3 h ago", "Yesterday", "3 days ago", "Sep 24", "Sep 24, 2025". */
export function fmtWhen(t: number, now = Date.now()): string {
  const mins = Math.floor((now - t) / 60_000);
  if (mins < 1) return "Just now";
  if (mins < 60) return `${mins} min ago`;
  const days = Math.round((startOfDay(now) - startOfDay(t)) / DAY);
  if (days === 0) return `${Math.floor(mins / 60)} h ago`;
  if (days === 1) return "Yesterday";
  if (days < 7) return `${days} days ago`;
  const d = new Date(t);
  const sameYear = d.getFullYear() === new Date(now).getFullYear();
  return d.toLocaleDateString("en-US", sameYear ? { month: "short", day: "numeric" } : { month: "short", day: "numeric", year: "numeric" });
}

export const baseName = (p: string) => p.split(/[\\/]/).pop() ?? p;

export function dirName(p: string): string {
  const i = Math.max(p.lastIndexOf("/"), p.lastIndexOf("\\"));
  return i > 0 ? p.slice(0, i) : p;
}

/** Shortens the home directory to `~`. */
export function tildify(p: string, home: string | null): string {
  if (!home) return p;
  const h = home.replace(/[\\/]$/, "");
  return p === h ? "~" : p.startsWith(h + "/") || p.startsWith(h + "\\") ? "~" + p.slice(h.length) : p;
}
