// The colour theme: Graphite (default), Indigo or Paper. Set on <html> as
// data-theme; app.html applies the saved one before the first paint.
export const THEMES = [
  { id: "graphite", label: "Graphite" },
  { id: "indigo", label: "Indigo" },
  { id: "paper", label: "Paper" },
] as const;

export type ThemeId = (typeof THEMES)[number]["id"];

const KEY = "jreverse.theme";

function saved(): ThemeId {
  try {
    const t = localStorage.getItem(KEY);
    if (THEMES.some((x) => x.id === t)) return t as ThemeId;
  } catch {
    // No storage: the default.
  }
  return "graphite";
}

export const theme = $state({ id: saved() });

export function setTheme(id: ThemeId) {
  theme.id = id;
  document.documentElement.dataset.theme = id;
  try {
    localStorage.setItem(KEY, id);
  } catch {
    // Not remembered; the theme still applies now.
  }
}
