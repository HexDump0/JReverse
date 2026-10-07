<script lang="ts">
  import { onMount } from "svelte";
  import { open as pickPath } from "@tauri-apps/plugin-dialog";
  import { revealItemInDir } from "@tauri-apps/plugin-opener";
  import { getCurrentWebview } from "@tauri-apps/api/webview";
  import { homeDir } from "@tauri-apps/api/path";
  import {
    closeSession,
    errorMessage,
    exampleFile,
    forgetRecent,
    listClasses,
    loadProject,
    onEngineLog,
    onEngineStatus,
    openFile,
    peekFile,
    recentFiles,
    replaceRecents,
    type EngineStatus,
    type Peek,
    type Recent,
    type RecentView,
  } from "$lib/engine";
  import { baseName, dirName, tildify } from "$lib/format";
  import { fmtN } from "$lib/format";
  import { say, setTask } from "$lib/status.svelte";
  import { setTheme, theme, THEMES } from "$lib/theme.svelte";
  import AppBar, { type Menu, type MenuItem } from "$lib/shell/AppBar.svelte";
  import StatusBar from "$lib/shell/StatusBar.svelte";
  import Palette, { type PaletteItem } from "$lib/shell/Palette.svelte";
  import Shortcuts from "$lib/shell/Shortcuts.svelte";
  import StartScreen from "$lib/start/StartScreen.svelte";
  import Onboarding, { onboarded } from "$lib/start/Onboarding.svelte";
  import Workbench from "$lib/workbench/Workbench.svelte";
  import { Workspace } from "$lib/workbench/workspace.svelte";

  const MAX_LOG = 500;
  /** `VITE_FIRST_RUN=1` shows onboarding on every launch and hides recent files, as a new user sees it. */
  const FIRST_RUN = import.meta.env.VITE_FIRST_RUN === "1";
  const FILTERS = [
    { name: "Java & Android", extensions: ["apk", "aab", "aar", "jar", "war", "dex", "class", "zip"] },
    { name: "All files", extensions: ["*"] },
  ];

  let recents = $state<RecentView[]>([]);
  let home = $state<string | null>(null);
  let example = $state<{ path: string; peek: Peek } | null>(null);
  let ws = $state<Workspace | null>(null);
  let workbench = $state<Workbench>();
  let readout = $state<Peek | null>(null);
  let opening = $state<{ path: string; peek: Peek; phase: "open" | "list"; fresh: boolean } | null>(null);
  let drag = $state(false);
  let onboarding = $state(FIRST_RUN || !onboarded());
  let paletteOpen = $state(false);
  let shortcutsOpen = $state(false);
  let engine = $state<EngineStatus | null>(null);
  let log = $state<string[]>([]);
  let logOpen = $state(false);
  let undoList: Recent[] | null = null;

  // Bumped by every open and cancel; a stale open drops its result.
  let openToken = 0;

  const openingText = $derived.by(() => {
    if (!opening) return "";
    if (opening.phase === "list") return "Reading the class list";
    if (engine?.state === "starting") return "Starting the engine";
    return `Loading classes with ${engine?.state === "ready" ? engine.engines[0] : "jadx"}`;
  });

  async function refreshRecents() {
    recents = await recentFiles();
    if (!example) {
      const path = await exampleFile();
      if (path) example = { path, peek: await peekFile(path) };
    }
  }

  async function browse() {
    const path = await pickPath({ multiple: false, directory: false, filters: FILTERS });
    if (path) openPath(path);
  }

  /** `deobfuscate` overrides what the file's saved project says. */
  async function openPath(path: string, o: { deobfuscate?: boolean } = {}) {
    if (opening?.path === path) return;
    const token = ++openToken;
    const name = baseName(path);
    let peek: Peek;
    try {
      peek = await peekFile(path);
    } catch (e) {
      if (token === openToken) say(`Can't read ${name}: ${errorMessage(e)}`, true);
      return;
    }
    if (token !== openToken) return;
    readout = peek;
    if (!peek.kind) {
      opening = null;
      setTask("");
      say(`Can't open ${name}`, true);
      return;
    }
    const fresh = !recents.some((r) => r.path === path);
    opening = { path, peek, phase: "open", fresh };
    setTask(`Opening ${name}`);
    try {
      const deobfuscate = o.deobfuscate ?? (await loadProject(path).catch(() => null))?.deobfuscate ?? false;
      const opened = await openFile(path, deobfuscate);
      if (token !== openToken) {
        closeSession(opened.session).catch(() => {});
        if (fresh) recents = await forgetRecent(path);
        return;
      }
      opening.phase = "list";
      const classes = await listClasses(opened.session);
      if (token !== openToken) {
        closeSession(opened.session).catch(() => {});
        return;
      }
      if (ws) {
        await ws.save();
        closeSession(ws.session).catch(() => {});
      }
      ws = new Workspace(path, name, opened, classes);
      readout = null;
      say(`Opened ${name} in ${opened.ms} ms`);
    } catch (e) {
      if (token === openToken) say(`Couldn't open ${name}: ${errorMessage(e)}`, true);
    } finally {
      if (token === openToken) {
        opening = null;
        setTask("");
      }
      refreshRecents();
    }
  }

  function cancelOpening() {
    if (!opening) return;
    const name = baseName(opening.path);
    openToken++;
    opening = null;
    readout = null;
    setTask("");
    say(`Cancelled opening ${name}`);
  }

  async function closeFile() {
    if (!ws) return;
    const closing = ws;
    ws = null;
    await closing.save();
    closeSession(closing.session).catch(() => {});
    say(`Closed ${closing.name}`);
    refreshRecents();
  }

  /* ---------- recent files ---------- */
  const strip = (list: RecentView[]): Recent[] => list.map(({ missing: _, ...r }) => r);

  async function remove(r: RecentView) {
    undoList = strip(recents);
    recents = await forgetRecent(r.path);
    say(`Removed ${baseName(r.path)} from recent files. Press Ctrl Z to undo.`);
  }

  async function clearRecents() {
    if (recents.length < 2) return;
    undoList = strip(recents);
    const n = recents.length - 1;
    recents = await replaceRecents(strip(recents.slice(0, 1)));
    say(`Cleared ${n} recent files. Press Ctrl Z to undo.`);
  }

  async function undo() {
    if (!undoList) return;
    recents = await replaceRecents(undoList);
    undoList = null;
    say("Restored recent files");
  }

  async function locate(r: RecentView) {
    const path = await pickPath({ multiple: false, directory: false, defaultPath: dirName(r.path), filters: FILTERS });
    if (!path) return;
    recents = await forgetRecent(r.path);
    openPath(path);
  }

  function reveal(r: RecentView) {
    revealItemInDir(r.path).catch((e) => say(`Can't show ${baseName(r.path)}: ${errorMessage(e)}`, true));
  }

  /* ---------- menus, palette, keys ---------- */
  const menus = $derived.by<Menu[]>(() => {
    const file: Menu = {
      label: "File",
      items: [
        { label: "Open file", key: "Ctrl O", run: browse },
        ...(ws && workbench ? workbench.fileItems() : []),
        { label: "Close file", key: "Ctrl Shift W", disabled: !ws, run: closeFile },
        { label: "Clear recent files", disabled: !!ws || recents.length < 2, run: clearRecents },
      ],
    };
    const always: (MenuItem | "-")[] = [
      { label: "Go to anything", key: "Ctrl P", run: () => (paletteOpen = true) },
      { label: "Keyboard shortcuts", key: "F1", run: () => (shortcutsOpen = true) },
      { label: logOpen ? "Hide log" : "Show log", run: () => (logOpen = !logOpen) },
      "-",
    ];
    const themes: (MenuItem | "-")[] = ["-", ...THEMES.map((t) => ({ label: `${t.label} theme`, checked: theme.id === t.id, run: () => setTheme(t.id) }))];
    if (ws && workbench) {
      const more = workbench.menus();
      const view = more.find((m) => m.label === "View");
      if (view) view.items = [...always, ...view.items, ...themes];
      return [file, ...more];
    }
    return [file, { label: "View", items: [...always.slice(0, -1), ...themes] }];
  });

  const paletteItems = $derived.by((): PaletteItem[] => {
    const items: PaletteItem[] = [];
    if (ws && workbench) {
      items.push(...workbench.paletteItems());
    } else {
      for (const r of recents) {
        if (r.missing) continue;
        items.push({ section: "Recent files", label: baseName(r.path), sub: tildify(dirName(r.path), home), run: () => openPath(r.path) });
      }
    }
    const act = (label: string, sub: string, run: () => void) => items.push({ section: "Actions", label, sub, action: true, run });
    act("Open file", "Ctrl O", browse);
    if (ws && workbench) {
      for (const m of workbench.menus()) for (const it of m.items) if (it !== "-" && !it.disabled) act(it.label, it.key ?? "", it.run);
      for (const it of workbench.fileItems()) if (!it.disabled) act(it.label, it.key ?? "", it.run);
      act("Close file", "Ctrl Shift W", closeFile);
    }
    else if (recents.length > 1) act("Clear recent files", "", clearRecents);
    act("Keyboard shortcuts", "F1", () => (shortcutsOpen = true));
    act(logOpen ? "Hide log" : "Show log", "", () => (logOpen = !logOpen));
    for (const t of THEMES) if (t.id !== theme.id) act(`${t.label} theme`, "", () => setTheme(t.id));
    return items;
  });

  function onkeydown(e: KeyboardEvent) {
    if (e.key === "F1" && !onboarding && !paletteOpen) {
      e.preventDefault();
      shortcutsOpen = true;
      return;
    }
    if (onboarding || paletteOpen || shortcutsOpen || !(e.ctrlKey || e.metaKey)) return;
    const key = e.key.toLowerCase();
    const inInput = e.target instanceof HTMLInputElement;
    // Select all belongs to fields and the code view, never the whole window.
    if (key === "a" && !inInput && !(e.target instanceof HTMLTextAreaElement) && !(e.target as Element).closest?.(".code")) {
      e.preventDefault();
      return;
    }
    if (key === "o") browse();
    else if (key === "p") paletteOpen = !opening;
    else if (key === "w" && e.shiftKey && ws) closeFile();
    else if (key === "z" && !inInput && !ws) undo();
    else return;
    e.preventDefault();
  }

  onMount(() => {
    refreshRecents();
    homeDir().then((h) => (home = h), () => {});
    const unlisten = [
      onEngineStatus((s) => {
        engine = s;
        if (s.state === "failed") say(`The engine failed to start: ${s.message}`, true);
        else if (s.state === "crashed") say("The engine stopped. It restarts on the next request.", true);
      }),
      onEngineLog((line) => {
        log.push(line);
        if (log.length > MAX_LOG) log.splice(0, log.length - MAX_LOG);
      }),
      getCurrentWebview().onDragDropEvent((e) => {
        const p = e.payload;
        if (p.type === "enter") drag = p.paths.length > 0;
        else if (p.type === "leave") drag = false;
        else if (p.type === "drop") {
          drag = false;
          onboarding = false;
          if (p.paths.length > 0) openPath(p.paths[0]);
        }
      }),
    ];
    return () => unlisten.forEach((p) => p.then((off) => off()));
  });
</script>

<svelte:window {onkeydown} />

<div class="app">
  <AppBar {menus} location={ws && workbench ? workbench.location() : null} bare={onboarding} onpalette={() => (paletteOpen = !opening)} />

  {#if onboarding}
    <Onboarding onfinish={() => (onboarding = false)} />
  {:else if ws}
    {#key ws}
      <Workbench {ws} {home} bind:this={workbench} onreopen={(d) => ws && openPath(ws.path, { deobfuscate: d })} />
    {/key}
  {:else}
    <StartScreen
      recents={FIRST_RUN ? [] : recents}
      {example}
      {home}
      {readout}
      opening={opening && { path: opening.path, peek: opening.peek, text: openingText }}
      active={!paletteOpen}
      onopen={openPath}
      onbrowse={browse}
      oncancel={cancelOpening}
      ondismiss={() => (readout = null)}
      onremove={remove}
      onreveal={reveal}
      onlocate={locate}
      onclear={clearRecents}
    />
  {/if}

  {#if logOpen}
    <section class="log island" aria-label="Engine log">
      <pre>{log.length ? log.join("\n") : "The engine hasn't logged anything yet."}</pre>
    </section>
  {/if}

  {#if !onboarding}
    <StatusBar
      {engine}
      info={ws ? `${ws.opened.kind.toUpperCase()}, ${fmtN(ws.classes.length)} classes` : ""}
      where={ws && workbench ? workbench.where() : []}
      inspector={ws && workbench ? workbench.inspectorState() : null}
      ontoggleinspector={() => workbench?.toggleInspector()}
      {logOpen}
      ontogglelog={() => (logOpen = !logOpen)}
    />
  {/if}
</div>

{#if drag && !onboarding}
  <div class="drop" aria-hidden="true">
    <div>
      <h2>Drop to open</h2>
      <p>APK, AAB, AAR, JAR, WAR, DEX or a class file{ws ? `. ${ws.name} is saved and closed first.` : ""}</p>
    </div>
  </div>
{/if}

{#if shortcutsOpen}
  <Shortcuts onclose={() => (shortcutsOpen = false)} />
{/if}

{#if paletteOpen}
  <Palette
    items={paletteItems}
    placeholder={ws ? "Go to a class, @ for a member, : for a line, > for actions" : "Open a recent file, or type > for actions"}
    onclose={() => (paletteOpen = false)}
    ongotoline={ws ? (n) => workbench?.jumpToLine(n) : undefined}
  />
{/if}

<style>
  .app {
    height: 100vh;
    display: flex;
    flex-direction: column;
    overflow: hidden;
    background: var(--frame);
  }
  .drop {
    position: fixed;
    inset: 0;
    z-index: 80;
    display: grid;
    place-items: center;
    text-align: center;
    background: color-mix(in srgb, var(--frame) 94%, transparent);
    pointer-events: none;
  }
  .drop::before {
    content: "";
    position: absolute;
    inset: 12px;
    border: 1.5px solid var(--accent);
    border-radius: 14px;
  }
  .drop h2 {
    margin: 0 0 8px;
    font: 600 30px var(--font-ui);
    color: var(--text-hi);
  }
  .drop p {
    margin: 0;
    color: var(--text-2);
  }
  .log {
    flex: none;
    height: 200px;
    margin: 6px 6px 0 56px;
    overflow: auto;
  }
  .log pre {
    user-select: text;
    margin: 0;
    padding: 12px 16px;
    font: 12px/1.6 var(--font-code);
    color: var(--text-2);
  }
</style>
