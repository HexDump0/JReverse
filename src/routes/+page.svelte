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
  import { say, setTask } from "$lib/status.svelte";
  import AppBar, { type Menu } from "$lib/shell/AppBar.svelte";
  import StatusBar from "$lib/shell/StatusBar.svelte";
  import Palette, { type PaletteItem } from "$lib/shell/Palette.svelte";
  import Shortcuts from "$lib/shell/Shortcuts.svelte";
  import StartScreen from "$lib/start/StartScreen.svelte";
  import Onboarding, { onboarded } from "$lib/start/Onboarding.svelte";
  import Workbench from "$lib/workbench/Workbench.svelte";
  import { simpleName, Workspace } from "$lib/workbench/workspace.svelte";

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
  let resume = $state<{ path: string; at: string | null; notes: number } | null>(null);
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
    loadResume(recents[0]);
    if (!example) {
      const path = await exampleFile();
      if (path) example = { path, peek: await peekFile(path) };
    }
  }

  // What the Continue card resumes into, from the file's saved project.
  async function loadResume(r: RecentView | undefined) {
    if (!r || r.missing) return (resume = null);
    const p = await loadProject(r.path).catch(() => null);
    if (!p) return (resume = null);
    const cls = p.active?.startsWith("class:") ? p.active.slice(6) : null;
    const notes = Object.keys(p.renames).length + Object.keys(p.comments).length + p.bookmarks.length;
    resume = { path: r.path, at: cls ? (p.renames[cls] ?? simpleName(cls)) : null, notes };
  }

  async function browse() {
    const path = await pickPath({ multiple: false, directory: false, filters: FILTERS });
    if (path) openPath(path);
  }

  async function openPath(path: string) {
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
      const opened = await openFile(path);
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
    const always = [
      { label: "Go to anything", key: "Ctrl P", run: () => (paletteOpen = true) },
      { label: "Keyboard shortcuts", key: "F1", run: () => (shortcutsOpen = true) },
      { label: logOpen ? "Hide log" : "Show log", run: () => (logOpen = !logOpen) },
    ];
    if (ws && workbench) {
      const more = workbench.menus();
      const view = more.find((m) => m.label === "View");
      if (view) view.items = [...always, ...view.items];
      return [file, ...more];
    }
    return [file, { label: "View", items: always }];
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
      for (const m of workbench.menus()) for (const it of m.items) if (!it.disabled) act(it.label, it.key ?? "", it.run);
      for (const it of workbench.fileItems()) if (!it.disabled) act(it.label, it.key ?? "", it.run);
      act("Close file", "Ctrl Shift W", closeFile);
    }
    else if (recents.length > 1) act("Clear recent files", "", clearRecents);
    act("Keyboard shortcuts", "F1", () => (shortcutsOpen = true));
    act(logOpen ? "Hide log" : "Show log", "", () => (logOpen = !logOpen));
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
  <AppBar {menus} bare={onboarding} onpalette={() => (paletteOpen = !opening)} />

  {#if onboarding}
    <Onboarding onfinish={() => (onboarding = false)} />
  {:else if ws}
    {#key ws}
      <Workbench {ws} {home} bind:this={workbench} />
    {/key}
  {:else}
    <StartScreen
      recents={FIRST_RUN ? [] : recents}
      {example}
      {resume}
      {home}
      {readout}
      opening={opening && { path: opening.path, peek: opening.peek, text: openingText }}
      {drag}
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
    <section class="log" aria-label="Engine log">
      <pre>{log.length ? log.join("\n") : "The engine hasn't logged anything yet."}</pre>
    </section>
  {/if}

  {#if !onboarding}
    <StatusBar {logOpen} ontogglelog={() => (logOpen = !logOpen)} where={ws && workbench ? workbench.where() : []} />
  {/if}
</div>

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
    background: var(--ground);
  }
  .log {
    flex: none;
    height: 200px;
    overflow: auto;
    background: var(--side);
    border-top: 1px solid var(--line);
  }
  .log pre {
    margin: 0;
    padding: 10px 14px;
    font: 11.5px/1.6 var(--font-code);
    color: var(--muted);
  }
</style>
