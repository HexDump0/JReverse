<script lang="ts">
  // Engine test bench: proves open → list → decompile end to end.
  // Deliberately plain; the real shell (design/mockup.html) replaces it.
  import { onMount } from "svelte";
  import { open } from "@tauri-apps/plugin-dialog";
  import { getCurrentWebview } from "@tauri-apps/api/webview";
  import {
    closeSession,
    decompileClass,
    errorMessage,
    listClasses,
    onEngineLog,
    onEngineStatus,
    openFile,
    type ClassEntry,
    type ClassKind,
    type EngineStatus,
    type Opened,
  } from "$lib/engine";

  const MAX_ROWS = 500;
  const MAX_LOG = 500;
  const BADGE: Record<ClassKind, string> = {
    class: "C",
    interface: "I",
    enum: "E",
    annotation: "@",
    record: "R",
  };

  let file = $state<{ name: string; opened: Opened } | null>(null);
  let classes = $state<ClassEntry[]>([]);
  let filter = $state("");
  let selected = $state<string | null>(null);
  let source = $state("");
  let busy = $state(false);
  let message = $state("Open an APK, AAR, JAR, DEX or class file (or drop one on the window).");
  let failed = $state(false);
  let engine = $state<EngineStatus | null>(null);
  let log = $state<string[]>([]);
  let showLog = $state(false);

  // Only the latest decompile request may update the view.
  let latest = 0;

  const matches = $derived.by(() => {
    const q = filter.trim().toLowerCase().replaceAll(".", "/");
    const all = q ? classes.filter((c) => c.id.toLowerCase().includes(q)) : classes;
    return { total: all.length, rows: all.slice(0, MAX_ROWS) };
  });

  const engineLabel = $derived.by(() => {
    switch (engine?.state) {
      case undefined:
        return "engine idle";
      case "starting":
        return "engine starting…";
      case "ready":
        return `engine ${engine.version} · ${engine.engines.join(", ")}`;
      case "stopped":
        return "engine stopped";
      case "crashed":
        return "engine crashed · restarts on next request";
      case "failed":
        return `engine failed: ${engine.message}`;
    }
  });

  function status(text: string, isError = false) {
    message = text;
    failed = isError;
  }

  async function pickFile() {
    const path = await open({
      multiple: false,
      directory: false,
      filters: [
        { name: "Java & Android", extensions: ["apk", "aar", "jar", "war", "dex", "class", "zip"] },
        { name: "All files", extensions: ["*"] },
      ],
    });
    if (path) await openPath(path);
  }

  async function openPath(path: string) {
    const name = path.split(/[\\/]/).pop() ?? path;
    busy = true;
    status(`Opening ${name}…`);
    try {
      const opened = await openFile(path);
      const list = await listClasses(opened.session);
      if (file) closeSession(file.opened.session).catch(() => {});
      file = { name, opened };
      classes = list;
      filter = "";
      selected = null;
      source = "";
      status(`Opened ${name} in ${opened.ms} ms`);
    } catch (e) {
      status(`Could not open ${name}: ${errorMessage(e)}`, true);
    } finally {
      busy = false;
    }
  }

  async function show(cls: ClassEntry) {
    if (!file) return;
    const token = ++latest;
    selected = cls.id;
    busy = true;
    try {
      const out = await decompileClass(file.opened.session, cls.id);
      if (token !== latest) return;
      source = out.source;
      const warn = out.warnings ? `, ${out.warnings} warning${out.warnings === 1 ? "" : "s"}` : "";
      status(`${out.engine}: decompiled in ${out.ms} ms${warn}`);
    } catch (e) {
      if (token !== latest) return;
      source = "";
      status(`Could not decompile ${dotted(cls.id)}: ${errorMessage(e)}`, true);
    } finally {
      if (token === latest) busy = false;
    }
  }

  function dotted(id: string) {
    return id.replaceAll("/", ".");
  }

  onMount(() => {
    const unlisten = [
      onEngineStatus((s) => (engine = s)),
      onEngineLog((line) => {
        log.push(line);
        if (log.length > MAX_LOG) log.splice(0, log.length - MAX_LOG);
      }),
      getCurrentWebview().onDragDropEvent((e) => {
        if (e.payload.type === "drop" && e.payload.paths.length > 0) openPath(e.payload.paths[0]);
      }),
    ];
    return () => unlisten.forEach((p) => p.then((off) => off()));
  });
</script>

<div class="app">
  <header>
    <button onclick={pickFile} disabled={busy}>Open…</button>
    {#if file}
      <span class="file">{file.name}</span>
      <span class="meta">{file.opened.kind} · {file.opened.classCount.toLocaleString()} classes</span>
    {/if}
  </header>

  <aside>
    <input placeholder="Filter classes" bind:value={filter} disabled={!file} spellcheck="false" />
    <ul>
      {#each matches.rows as cls (cls.id)}
        <li>
          <button class:selected={cls.id === selected} onclick={() => show(cls)} title={dotted(cls.id)}>
            <span class="badge">{BADGE[cls.kind]}</span>
            <span class="name">{dotted(cls.id)}</span>
          </button>
        </li>
      {/each}
    </ul>
    {#if matches.total > MAX_ROWS}
      <p class="more">{(matches.total - MAX_ROWS).toLocaleString()} more; refine the filter</p>
    {/if}
  </aside>

  <main>
    {#if source}
      <pre>{source}</pre>
    {:else}
      <p class="empty">{file ? "Pick a class." : ""}</p>
    {/if}
  </main>

  {#if showLog}
    <section class="log">
      <pre>{log.join("\n")}</pre>
    </section>
  {/if}

  <footer>
    <span class="message" class:failed>{busy ? "Working…" : message}</span>
    <span class="engine">{engineLabel}</span>
    <button class="toggle" class:on={showLog} onclick={() => (showLog = !showLog)}>Log</button>
  </footer>
</div>

<style>
  .app {
    display: grid;
    grid-template-columns: minmax(220px, 320px) 1fr;
    grid-template-rows: auto 1fr auto auto;
    grid-template-areas: "header header" "aside main" "log log" "footer footer";
    height: 100vh;
  }

  header,
  footer {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 6px 10px;
    background: var(--ground);
  }
  header {
    grid-area: header;
    border-bottom: 1px solid var(--line);
  }
  footer {
    grid-area: footer;
    border-top: 1px solid var(--line);
    color: var(--muted);
  }

  button {
    font: inherit;
    color: var(--text);
    background: var(--line-2);
    border: 1px solid var(--line-2);
    border-radius: 4px;
    padding: 3px 10px;
    cursor: pointer;
  }
  button:disabled {
    opacity: 0.5;
    cursor: default;
  }

  .file {
    font-weight: 600;
  }
  .meta {
    color: var(--muted);
  }

  aside {
    grid-area: aside;
    display: flex;
    flex-direction: column;
    min-height: 0;
    background: var(--side);
    border-right: 1px solid var(--line);
  }
  input {
    margin: 8px;
    padding: 4px 8px;
    font: inherit;
    color: var(--text);
    background: var(--pane);
    border: 1px solid var(--line-2);
    border-radius: 4px;
  }
  input:focus {
    outline: 1px solid var(--accent);
  }
  ul {
    flex: 1;
    margin: 0;
    padding: 0;
    list-style: none;
    overflow: auto;
  }
  li button {
    display: flex;
    align-items: center;
    gap: 6px;
    width: 100%;
    padding: 2px 8px;
    text-align: left;
    background: none;
    border: none;
    border-radius: 0;
    white-space: nowrap;
  }
  li button:hover {
    background: var(--curline);
  }
  li button.selected {
    background: var(--sel);
  }
  .badge {
    flex: none;
    width: 16px;
    height: 16px;
    border-radius: 50%;
    font-size: 10px;
    line-height: 16px;
    text-align: center;
    color: var(--pane);
    background: var(--muted);
  }
  .name {
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .more {
    margin: 0;
    padding: 6px 8px;
    color: var(--faint);
  }

  main {
    grid-area: main;
    min-width: 0;
    overflow: auto;
    background: var(--pane);
  }
  pre {
    margin: 0;
    padding: 12px 16px;
    font-family: var(--font-code);
    font-size: 13px;
    line-height: 1.6;
    font-variant-ligatures: none;
    tab-size: 4;
  }
  .empty {
    padding: 16px;
    color: var(--faint);
  }

  .log {
    grid-area: log;
    height: 200px;
    overflow: auto;
    background: var(--side);
    border-top: 1px solid var(--line);
  }
  .log pre {
    font-size: 11.5px;
    color: var(--muted);
  }

  .message {
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .message.failed {
    color: var(--error);
  }
  .toggle {
    padding: 1px 8px;
    background: none;
  }
  .toggle.on {
    border-color: var(--accent);
    color: var(--accent);
  }
</style>
