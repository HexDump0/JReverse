<script lang="ts">
  // The first tab: what this file is, what stands out, and where to start reading.
  import type { Cert, Component, PluginInfo } from "$lib/engine";
  import Icon from "$lib/Icon.svelte";
  import { fmtN, fmtSize, kindLabel, tildify } from "$lib/format";
  import { say } from "$lib/status.svelte";
  import { androidVersion, groupClasses, libraryOf, looksObfuscated, permissionLabel, permissionLevel, type PermissionLevel } from "./android";
  import { dotted, type Workspace } from "./workspace.svelte";

  interface Props {
    ws: Workspace;
    home: string | null;
    /** Narrows the class tree to a package. */
    onfilter: (prefix: string) => void;
    /** Opens the file again with jadx's generated names on or off. */
    onreopen: (deobfuscate: boolean) => void;
  }

  let { ws, home, onfilter, onreopen }: Props = $props();

  const COMPONENTS_SHOWN = 8;
  const LIBS_SHOWN = 8;

  let allComponents = $state(false);
  let allPermissions = $state(false);
  let allLibs = $state(false);
  let allLinks = $state(false);

  const o = $derived(ws.info);
  const a = $derived(o?.android);
  const plugins = $derived(o?.plugins ?? []);
  const plugin = $derived(plugins[0]);
  const jm = $derived(o?.jarManifest ?? {});

  /** One row per way into the code: manifest classes, descriptor entrypoints, annotated classes, web.xml. */
  interface Entry {
    cls: string;
    label: string;
    detail?: string;
  }
  const entries = $derived.by((): Entry[] => {
    if (!o || a) return [];
    const out: Entry[] = [];
    const MANIFEST: [string, string][] = [
      ["Main-Class", "main class"],
      ["Start-Class", "Spring Boot start class"],
      ["Premain-Class", "agent, loaded at startup"],
      ["Agent-Class", "agent, loaded on attach"],
      ["Launcher-Agent-Class", "launcher agent"],
      ["Plugin-Class", "plugin class"],
    ];
    for (const [key, label] of MANIFEST) if (jm[key]) out.push({ cls: jm[key], label });
    for (const p of plugins) {
      for (const e of p.entries) {
        const label = p.loader === "fabric" || p.loader === "quilt" ? `${e.kind} entrypoint` : "plugin main class";
        out.push({ cls: e.cls, label, detail: e.member });
      }
    }
    const ENTRY_LABEL: Record<string, string> = {
      neoforge: "@Mod",
      forge: "@Mod",
      servlet: "servlet",
      filter: "filter",
      listener: "listener",
      "spring-boot": "@SpringBootApplication",
    };
    for (const e of o.entryClasses ?? []) {
      // @Mod carries the mod id; servlets and filters their URL patterns.
      const detail = e.kind.endsWith("forge") ? e.detail[0] && `mod id ${e.detail[0]}` : e.detail.join(", ");
      out.push({ cls: e.cls, label: ENTRY_LABEL[e.kind] ?? e.kind, detail: detail || undefined });
    }
    for (const s of o.web?.servlets ?? []) if (s.cls) out.push({ cls: s.cls, label: "servlet", detail: s.urls.join(", ") || undefined });
    for (const f of o.web?.filters ?? []) if (f.cls) out.push({ cls: f.cls, label: "filter", detail: f.urls.join(", ") || undefined });
    for (const l of o.web?.listeners ?? []) out.push({ cls: l, label: "listener" });
    const seen = new Set<string>();
    return out.filter((e) => !seen.has(e.cls + e.label) && seen.add(e.cls + e.label));
  });

  /** The package the file's own code lives in, judged by its package name or its entry points. */
  const appPackage = $derived.by(() => {
    if (a?.package) return a.package;
    const first = entries[0]?.cls;
    if (!first || !first.includes(".")) return undefined;
    const parts = first.split(".").slice(0, -1);
    return parts.slice(0, Math.min(parts.length, 3)).join(".");
  });
  const makeup = $derived(groupClasses(ws.classes, appPackage));
  const libraries = $derived(makeup.groups.filter((g) => g.library));

  /** The class map: the app's own code, its obfuscated part, the Kotlin runtime, everything else known. */
  const map = $derived.by(() => {
    const parts = {
      app: { label: "App code", n: 0, color: "var(--m-app)", prefix: "" },
      obf: { label: "Obfuscated", n: 0, color: "var(--m-obf)", prefix: "" },
      kt: { label: "Kotlin runtime", n: 0, color: "var(--m-kt)", prefix: "kotlin" },
      lib: { label: "Libraries", n: 0, color: "var(--m-lib)", prefix: "" },
    };
    const obfPkgs = new Map<string, number>();
    for (const c of ws.classes) {
      const lib = libraryOf(c.id);
      if (lib) (lib.startsWith("Kotlin") ? parts.kt : parts.lib).n++;
      else if (looksObfuscated(c.id)) {
        parts.obf.n++;
        const pkg = c.id.slice(0, Math.max(0, c.id.lastIndexOf("/")));
        obfPkgs.set(pkg, (obfPkgs.get(pkg) ?? 0) + 1);
      } else parts.app.n++;
    }
    parts.app.prefix = appPackage ?? makeup.groups.find((g) => !g.library)?.prefix.replaceAll("/", ".") ?? "";
    parts.obf.prefix = [...obfPkgs.entries()].sort((x, y) => y[1] - x[1])[0]?.[0].replaceAll("/", ".") ?? "";
    const list = Object.values(parts).filter((p) => p.n > 0);
    // Widths with a floor, so a small part stays visible and clickable.
    const floor = list.map((p) => Math.max(p.n / Math.max(1, ws.classes.length), 0.04));
    const sum = floor.reduce((x, y) => x + y, 0);
    return list.map((p, i) => ({ ...p, w: floor[i] / sum }));
  });

  const LOADER: Record<PluginInfo["loader"], [string, string]> = {
    fabric: ["Fabric", "Fabric mod"],
    quilt: ["Quilt", "Quilt mod"],
    neoforge: ["NeoForge", "NeoForge mod"],
    forge: ["Forge", "Forge mod"],
    bukkit: ["Bukkit", "Bukkit plugin"],
    paper: ["Paper", "Paper plugin"],
    bungeecord: ["BungeeCord", "BungeeCord plugin"],
    velocity: ["Velocity", "Velocity plugin"],
  };

  const isWeb = $derived(!!o?.web || entries.some((e) => e.label === "servlet"));

  /** What sort of program this is, from the strongest evidence the file has. */
  const typeName = $derived.by(() => {
    if (plugin) return LOADER[plugin.loader][1];
    if (isWeb) return "Web app";
    if (jm["Start-Class"] || o?.entryClasses?.some((e) => e.kind === "spring-boot")) return "Spring Boot app";
    if (jm["Premain-Class"] || jm["Agent-Class"]) return "Java agent";
    if (jm["Main-Class"]) return "Runnable JAR";
    return kindLabel(ws.opened.kind);
  });

  const iconFor = $derived(
    ws.opened.kind === "apk" || ws.opened.kind === "aab"
      ? "android"
      : ws.opened.kind === "dex"
        ? "fileCode"
        : plugin
          ? "puzzle"
          : isWeb
            ? "world"
            : "coffee",
  );
  const title = $derived(a?.label && !a.label.startsWith("@") ? a.label : (plugin?.name ?? jm["Implementation-Title"] ?? ws.name));
  const subtitle = $derived(a?.package ?? plugin?.id);
  const version = $derived(a ? a.versionName : (plugin?.version ?? jm["Implementation-Version"]));
  const minecraft = $derived(plugin?.depends?.find((d) => d.id === "minecraft")?.version);
  const otherDeps = $derived((plugin?.depends ?? []).filter((d) => !["minecraft", "java", "fabricloader", "quilt_loader"].includes(d.id)));
  const ENVIRONMENT: Record<string, string> = { client: "Client only", server: "Server only", "*": "Client and server" };

  /** Mixin classes with what they change: from the configs, with targets read from the classes. */
  const mixins = $derived.by(() => {
    const targets = o?.mixinTargets ?? {};
    const rows = (o?.mixinConfigs ?? []).flatMap((c) => c.classes.map((m) => ({ cls: m.cls, side: m.side, targets: targets[m.cls] ?? [] })));
    const listed = new Set(rows.map((r) => r.cls));
    for (const [cls, t] of Object.entries(targets)) if (!listed.has(cls)) rows.push({ cls, side: "both", targets: t });
    return rows;
  });
  const changedClasses = $derived(new Set(mixins.flatMap((m) => m.targets)).size);
  const allProviders = $derived((o?.services ?? []).flatMap((s) => s.providers.map((p) => ({ service: s.service, cls: p }))));
  // Bundled libraries register their own services; the file's own ones are what you came for.
  const providers = $derived(allProviders.filter((p) => !libraryOf(classId(p.cls))));
  /** Fabric's production names (`class_1308`), which only a mappings file turns into real ones. */
  const intermediary = $derived(mixins.some((m) => m.targets.some((t) => /\.class_\d+/.test(t))));
  const jars = $derived(o?.jars ?? []);
  const artifacts = $derived(o?.artifacts ?? []);

  let allEntries = $state(false);
  let allMixins = $state(false);
  let allServices = $state(false);
  let allBundled = $state(false);

  const LEVEL_ORDER: Record<PermissionLevel, number> = { dangerous: 0, special: 1, custom: 2, normal: 3 };
  const permissions = $derived(
    (a?.permissions ?? [])
      .map((p) => ({ ...p, level: permissionLevel(p.name) }))
      .sort((x, y) => LEVEL_ORDER[x.level] - LEVEL_ORDER[y.level] || x.name.localeCompare(y.name)),
  );
  const dangerousCount = $derived(permissions.filter((p) => p.level === "dangerous").length);

  // Exported components are the app's attack surface; launcher first, then by type.
  const components = $derived(
    (a?.components ?? [])
      .filter((c) => c.exported || c.launcher)
      .sort((x, y) => Number(y.launcher) - Number(x.launcher) || x.type.localeCompare(y.type) || x.name.localeCompare(y.name)),
  );
  const deepLinks = $derived([...new Set((a?.components ?? []).flatMap((c) => c.links))].sort());

  const certs = $derived(o?.signing?.certs ?? []);

  interface Finding {
    level: "error" | "warn" | "info";
    title: string;
    text: string;
    action?: { label: string; run: () => void };
  }

  const findings = $derived.by((): Finding[] => {
    if (!o) return [];
    const f: Finding[] = [];
    if (a?.debuggable === "true") f.push({ level: "error", title: "Debuggable", text: "android:debuggable is on, so any debugger can attach to the app." });
    if (certs.some((c) => c.debug)) f.push({ level: "error", title: "Debug certificate", text: "Signed with the Android debug key, not a release key." });
    if (o.kind === "apk" && !o.signing?.schemes.length) f.push({ level: "warn", title: "Not signed", text: "No v1, v2 or v3 signature. Android won't install it as is." });
    if (a?.usesCleartextTraffic === "true") f.push({ level: "warn", title: "Cleartext traffic", text: "android:usesCleartextTraffic is on: plain HTTP is allowed." });
    if (a?.allowBackup === "true") f.push({ level: "warn", title: "Backups allowed", text: "android:allowBackup is on: app data can be copied off the device." });
    const implicit = (a?.components ?? []).filter((c) => c.exportedImplicitly);
    if (implicit.length) {
      f.push({
        level: "warn",
        title: `${implicit.length} implicitly exported`,
        text: `${implicit.length === 1 ? "A component has" : "Components have"} an intent filter but no android:exported, so other apps can start ${implicit.length === 1 ? "it" : "them"} (target SDK ${a?.targetSdk ?? "below 31"}).`,
      });
    }
    const share = makeup.obfuscated / Math.max(1, ws.classes.length);
    if (ws.opened.deobfuscated) {
      f.push({
        level: "info",
        title: "Generated names",
        text: "Short and clashing names show as jadx's generated ones, such as C0123a.",
        action: { label: "Show original names", run: () => onreopen(false) },
      });
    } else if (share >= 0.15) {
      f.push({
        level: "info",
        title: "Likely obfuscated",
        text: `${Math.round(share * 100)}% of classes have one- or two-letter names.`,
        action: { label: "Use generated names", run: () => onreopen(true) },
      });
    }
    return f;
  });

  const classId = (javaName: string | undefined) => (javaName ? javaName.replaceAll(".", "/") : "");
  /** Whether the class, or the top-level class an inner one sits in, is in this file. */
  const has = (javaName: string | undefined) => !!javaName && ws.byId.has(classId(javaName).split("$")[0]);
  const simple = (javaName: string) => javaName.slice(javaName.lastIndexOf(".") + 1);

  function open(javaName: string | undefined) {
    if (has(javaName)) ws.openClass(classId(javaName));
  }

  async function copy(text: string, what: string) {
    try {
      await navigator.clipboard.writeText(text);
      say(`Copied the ${what}`);
    } catch {
      say("Couldn't copy to the clipboard", true);
    }
  }

  const date = (iso: string) => new Date(iso).toLocaleDateString("en-US", { year: "numeric", month: "short", day: "numeric" });
  const expired = (c: Cert) => new Date(c.notAfter).getTime() < Date.now();
  const cn = (dn: string) => /CN=([^,]+)/.exec(dn)?.[1] ?? dn;
  const short = (name: string) => (a?.package && name.startsWith(a.package + ".") ? name.slice(a.package.length) : name);
  const typeLabel: Record<Component["type"], string> = { activity: "Activity", service: "Service", receiver: "Receiver", provider: "Provider" };
  const libsByAbi = $derived(
    Object.entries(
      (o?.nativeLibs ?? []).reduce<Record<string, string[]>>((m, l) => ((m[l.abi] ??= []).push(l.name), m), {}),
    ).sort(),
  );
</script>

{#snippet cls(name: string, label: string = name)}{#if has(name)}<button class="cl" onclick={() => open(name)}>{label}</button>{:else}{label}{/if}{/snippet}

<div class="page selectable">
  <div class="in">
    <header class="ident">
      <span class="appicon"><Icon name={iconFor} size={26} /></span>
      <div class="who">
        <h1>{title}</h1>
        {#if subtitle}<p class="pk">{subtitle}</p>{:else}<p class="pk" title={ws.path}>{tildify(ws.path, home)}</p>{/if}
      </div>
      <dl class="facts">
        {#if version || a?.versionCode}
          <div><dt>Version</dt><dd>{version ?? ""}{#if a?.versionCode}{" "}<span class="dim">({a.versionCode})</span>{/if}</dd></div>
        {/if}
        {#if a?.minSdk || a?.targetSdk}
          <div><dt>SDK</dt><dd>{a.minSdk ?? "?"} to {a.targetSdk ?? "?"}</dd></div>
        {:else}
          <div><dt>Type</dt><dd>{typeName}</dd></div>
        {/if}
        {#if minecraft}<div><dt>Minecraft</dt><dd>{minecraft}</dd></div>{/if}
        {#if o}<div><dt>Size</dt><dd>{fmtSize(o.size)}</dd></div>{/if}
        <div><dt>Classes</dt><dd>{fmtN(ws.classes.length)}</dd></div>
        {#if o && !a}<div><dt>Methods</dt><dd>{fmtN(o.methods)}</dd></div>{/if}
      </dl>
    </header>

    {#if map.length > 1}
      <section class="map" aria-label="Class map">
        <div class="map-head"><h2>Class map</h2><span>Click a part to show it in the class tree</span></div>
        <div class="bar">
          {#each map as p (p.label)}
            <button style:flex={p.w} style:background={p.color} title="{p.label}: {fmtN(p.n)} classes" disabled={!p.prefix} onclick={() => onfilter(p.prefix)}></button>
          {/each}
        </div>
        <div class="legend">
          {#each map as p (p.label)}
            <button disabled={!p.prefix} onclick={() => onfilter(p.prefix)}><i style:background={p.color}></i>{p.label}<b>{fmtN(p.n)}</b></button>
          {/each}
        </div>
        {#if libraries.length}
          <p class="libs">
            <span class="dim">Libraries found</span>
            {#each allLibs ? libraries : libraries.slice(0, LIBS_SHOWN) as g, i (g.name)}{#if i}{", "}{/if}<button class="lib" onclick={() => g.prefix && onfilter(dotted(g.prefix))}>{g.name}</button>{" "}<span class="dim">{fmtN(g.classes)}</span>{/each}
            {#if libraries.length > LIBS_SHOWN}{" "}<button class="more" onclick={() => (allLibs = !allLibs)}>{allLibs ? "fewer" : `and ${libraries.length - LIBS_SHOWN} more`}</button>{/if}
          </p>
        {/if}
      </section>
    {/if}

    {#if !o}
      <p class="wait">
        {#if ws.infoError}Couldn't read the file's details: {ws.infoError}{:else}<i class="spin"></i>Reading the file{/if}
      </p>
    {:else}
      <div class="grid">
        {#if findings.length}
          <section>
            <h2>Worth a look <span class="n">{findings.length}</span></h2>
            {#each findings as f (f.title)}
              <div class="finding {f.level}">
                <Icon name={f.level === "info" ? "info" : "alert"} size={17} />
                <div><b>{f.title}</b><p>{f.text}</p></div>
                {#if f.action}<button class="more" onclick={f.action.run}>{f.action.label}</button>{/if}
              </div>
            {/each}
          </section>
        {/if}

        {#if a}
          <section>
            <h2>App</h2>
            <dl class="kv">
              <dt>Package</dt>
              <dd class="mono">{a.package}</dd>
              {#if a.versionName || a.versionCode}
                <dt>Version</dt>
                <dd>{a.versionName ?? ""}{#if a.versionCode}<span class="dim">build {a.versionCode}</span>{/if}</dd>
              {/if}
              {#each [["Min SDK", a.minSdk], ["Target SDK", a.targetSdk], ["Compile SDK", a.compileSdk]] as [label, sdk] (label)}
                {#if sdk}
                  <dt>{label}</dt>
                  <dd>{sdk}{#if androidVersion(sdk)}<span class="dim">{androidVersion(sdk)}</span>{/if}</dd>
                {/if}
              {/each}
              {#if a.application}
                <dt>Application</dt>
                <dd class="mono">
                  {#if has(a.application)}<button class="cl" onclick={() => open(a.application)}>{short(a.application)}</button>{:else}{a.application}{/if}
                </dd>
              {/if}
              {#if a.networkSecurityConfig}
                <dt>Network config</dt>
                <dd class="mono">{a.networkSecurityConfig}</dd>
              {/if}
            </dl>
            {#if o.manifest}
              <button class="more" onclick={() => ws.showManifest()}>Open AndroidManifest.xml</button>
            {/if}
          </section>
        {/if}

        {#each plugins as p (p.file + (p.id ?? ""))}
          <section>
            <h2>{LOADER[p.loader][1].endsWith(" mod") ? "Mod" : "Plugin"}</h2>
            {#if p.description}<p class="desc">{p.description}</p>{/if}
            <dl class="kv">
              <dt>Loader</dt>
              <dd>{LOADER[p.loader][0]}{#if p.apiVersion}<span class="dim">API {p.apiVersion}</span>{/if}</dd>
              {#if p !== plugin && p.id}<dt>ID</dt><dd class="mono">{p.id}</dd>{/if}
              {#if p !== plugin && p.version}<dt>Version</dt><dd>{p.version}</dd>{/if}
              {#if p.authors.length}<dt>{p.authors.length === 1 ? "Author" : "Authors"}</dt><dd>{p.authors.join(", ")}</dd>{/if}
              {#if p.environment && ENVIRONMENT[p.environment]}<dt>Runs on</dt><dd>{ENVIRONMENT[p.environment]}</dd>{/if}
              {#if p.license}<dt>License</dt><dd>{p.license}</dd>{/if}
            </dl>
            {#if p === plugin && otherDeps.length}
              <h3>Depends on <span class="n">{otherDeps.length}</span></h3>
              {#each otherDeps as d (d.id)}
                <div class="tr"><span class="a mono">{d.id}</span><span class="b">{d.version === "*" ? "any version" : d.version}</span></div>
              {/each}
            {/if}
            <button class="more" onclick={() => ws.openFile(p.file)}>Open {p.file.slice(p.file.lastIndexOf("/") + 1)}</button>
          </section>
        {/each}

        {#if entries.length}
          <section>
            <h2>Entry points <span class="n">{entries.length}</span><span class="r">where the code starts running</span></h2>
            {#each allEntries ? entries : entries.slice(0, COMPONENTS_SHOWN) as e (e.cls + e.label)}
              <div class="tr">
                <span class="a mono" title={e.cls}>{@render cls(e.cls)}</span>
                {#if e.detail}<span class="dim mono detail" title={e.detail}>{e.detail}</span>{/if}
                <span class="b">{e.label}</span>
              </div>
            {/each}
            {#if entries.length > COMPONENTS_SHOWN}
              <button class="more" onclick={() => (allEntries = !allEntries)}>{allEntries ? "Show fewer" : `Show all ${entries.length}`}</button>
            {/if}
          </section>
        {/if}

        {#if mixins.length}
          <section>
            <h2>Mixins <span class="n">{mixins.length}</span>{#if changedClasses}<span class="r">change {changedClasses} {changedClasses === 1 ? "class" : "classes"}</span>{/if}</h2>
            {#each allMixins ? mixins : mixins.slice(0, COMPONENTS_SHOWN) as m (m.cls)}
              <div class="tr">
                <span class="a mono" title={m.cls}>{@render cls(m.cls, simple(m.cls))}</span>
                {#if m.side !== "both"}<span class="flag">{m.side}</span>{/if}
                <span class="b mono" title={m.targets.join(", ")}>{m.targets.map(simple).join(", ")}</span>
              </div>
            {/each}
            {#if mixins.length > COMPONENTS_SHOWN}
              <button class="more" onclick={() => (allMixins = !allMixins)}>{allMixins ? "Show fewer" : `Show all ${mixins.length}`}</button>
            {/if}
            {#if intermediary}<p class="note">Game classes are named as in Fabric's intermediary mappings, the way the mod ships.</p>{/if}
          </section>
        {/if}

        {#if providers.length}
          <section>
            <h2>Services <span class="n">{providers.length}</span><span class="r">{allProviders.length > providers.length ? `${allProviders.length - providers.length} more from libraries` : "from META-INF/services"}</span></h2>
            {#each allServices ? providers : providers.slice(0, COMPONENTS_SHOWN) as p (p.service + p.cls)}
              <div class="tr">
                <span class="a mono" title={p.cls}>{@render cls(p.cls, simple(p.cls))}</span>
                <span class="b mono" title={p.service}>{simple(p.service)}</span>
              </div>
            {/each}
            {#if providers.length > COMPONENTS_SHOWN}
              <button class="more" onclick={() => (allServices = !allServices)}>{allServices ? "Show fewer" : `Show all ${providers.length}`}</button>
            {/if}
          </section>
        {/if}

        {#if jars.length || artifacts.length}
          <section>
            <h2>Bundled <span class="n">{jars.length + artifacts.length}</span><span class="r">code shipped inside this file</span></h2>
            {#each allBundled ? jars : jars.slice(0, COMPONENTS_SHOWN) as j (j.path)}
              <div class="tr"><span class="a mono" title={j.path}>{j.path.slice(j.path.lastIndexOf("/") + 1)}</span><span class="b">{fmtSize(j.size)}</span></div>
            {/each}
            {#each allBundled ? artifacts : artifacts.slice(0, Math.max(0, COMPONENTS_SHOWN - jars.length)) as m (m.group + m.artifact)}
              <div class="tr"><span class="a mono" title="{m.group}:{m.artifact}"><span class="dim">{m.group}:</span>{m.artifact}</span><span class="b">{m.version ?? ""}</span></div>
            {/each}
            {#if jars.length + artifacts.length > COMPONENTS_SHOWN}
              <button class="more" onclick={() => (allBundled = !allBundled)}>{allBundled ? "Show fewer" : `Show all ${jars.length + artifacts.length}`}</button>
            {/if}
          </section>
        {/if}

        {#if o.jarManifest || o.javaVersions?.length}
          <section>
            <h2>Java</h2>
            <dl class="kv">
              {#if o.javaVersions?.length}
                <dt>Bytecode</dt>
                <dd>
                  {#each o.javaVersions as v, i (v.java)}{#if i}{", "}{/if}Java {v.java}{#if o.javaVersions.length > 1}<span class="dim">{fmtN(v.classes)}</span>{/if}{/each}
                </dd>
              {/if}
              {#each [["Title", "Implementation-Title"], ["Version", "Implementation-Version"], ["Vendor", "Implementation-Vendor"], ["Module", "Automatic-Module-Name"], ["Built with", "Build-Jdk-Spec"], ["Created by", "Created-By"]] as [label, key] (key)}
                {#if o.jarManifest?.[key]}
                  <dt>{label}</dt>
                  <dd>{o.jarManifest[key]}</dd>
                {/if}
              {/each}
            </dl>
          </section>
        {/if}

        {#if a}
          <section>
            <h2>Entry points <span class="n">{components.length}</span><span class="r">exported and launcher components</span></h2>
            {#if components.length}
              {#each allComponents ? components : components.slice(0, COMPONENTS_SHOWN) as c (c.type + c.name + (c.alias ?? ""))}
                <div class="tr">
                  <span class="a mono">{#if has(c.name)}<button class="cl" onclick={() => open(c.name)}>{short(c.name)}</button>{:else}{short(c.name)}{/if}</span>
                  {#if c.exportedImplicitly}<span class="flag warn">implicitly exported</span>{:else if c.exported && !c.launcher && !c.permission}<span class="flag bad">exported</span>{/if}
                  {#if c.permission}<span class="flag" title={c.permission}>needs permission</span>{/if}
                  <span class="b">{typeLabel[c.type].toLowerCase()}{c.launcher ? ", launcher" : ""}</span>
                </div>
              {/each}
              {#if components.length > COMPONENTS_SHOWN}
                <button class="more" onclick={() => (allComponents = !allComponents)}>{allComponents ? "Show fewer" : `Show all ${components.length}`}</button>
              {/if}
            {:else}
              <p class="dim">Nothing is exported.</p>
            {/if}
            {#if deepLinks.length}
              <h3>Deep links <span class="n">{deepLinks.length}</span></h3>
              {#each allLinks ? deepLinks : deepLinks.slice(0, 8) as l (l)}<div class="tr"><span class="a mono">{l}</span></div>{/each}
              {#if deepLinks.length > 8}
                <button class="more" onclick={() => (allLinks = !allLinks)}>{allLinks ? "Show fewer" : `Show all ${deepLinks.length}`}</button>
              {/if}
            {/if}
          </section>

          <section>
            <h2>
              Permissions <span class="n">{permissions.length}</span>
              {#if dangerousCount}<span class="r">{dangerousCount} dangerous</span>{/if}
            </h2>
            {#if permissions.length}
              {#each allPermissions ? permissions : permissions.slice(0, 12) as p (p.name)}
                <div class="tr">
                  <span class="a mono" class:hi={p.level === "dangerous" || p.level === "special"} title={p.name}>{permissionLabel(p.name)}</span>
                  {#if p.maxSdk}<span class="dim">up to SDK {p.maxSdk}</span>{/if}
                  <span class="b" class:warn={p.level === "dangerous"} class:bad={p.level === "special"}>{p.level}</span>
                </div>
              {/each}
              {#if permissions.length > 12}
                <button class="more" onclick={() => (allPermissions = !allPermissions)}>{allPermissions ? "Show fewer" : `Show all ${permissions.length}`}</button>
              {/if}
            {:else}
              <p class="dim">None requested.</p>
            {/if}
          </section>
        {/if}

        {#if o.signing && (o.signing.schemes.length || o.kind === "apk")}
          <section>
            <h2>Signing {#if o.signing.schemes.length}<span class="n">{o.signing.schemes.join(", ")}</span>{/if}<span class="r">read, not verified</span></h2>
            {#if !certs.length}
              <p class="dim">Not signed.</p>
            {/if}
            {#each certs as c (c.sha256)}
              <dl class="kv">
                <dt>Signer</dt>
                <dd title={c.subject}>{cn(c.subject)}</dd>
                {#if c.issuer !== c.subject}
                  <dt>Issuer</dt>
                  <dd title={c.issuer}>{cn(c.issuer)}</dd>
                {/if}
                <dt>Valid</dt>
                <dd class:warn={expired(c)}>{date(c.notBefore)} to {date(c.notAfter)}{#if expired(c)} (expired){/if}</dd>
                <dt>Key</dt>
                <dd>{c.key}<span class="dim">{c.algorithm}</span></dd>
                <dt>SHA-256</dt>
                <dd class="mono hash">
                  <span>{c.sha256}</span>
                  <button class="ib" title="Copy" onclick={() => copy(c.sha256, "SHA-256 fingerprint")}><Icon name="copy" size={14} /></button>
                </dd>
              </dl>
            {/each}
          </section>
        {/if}

        {#if libsByAbi.length || (o.dex?.length ?? 0) > 0}
          <section>
            <h2>Contents {#if o.files}<span class="n">{fmtN(o.files)} files</span>{/if}</h2>
            <dl class="kv">
              {#if o.dex?.length}
                <dt>DEX</dt>
                <dd>{o.dex.length === 1 ? "1 file" : `${o.dex.length} files`}<span class="dim">{fmtSize(o.dex.reduce((n, d) => n + d.size, 0))}</span></dd>
              {/if}
              {#each libsByAbi as [abi, names] (abi)}
                <dt class="mono">{abi}</dt>
                <dd class="mono libs2">{names.join(", ")}</dd>
              {/each}
            </dl>
            {#if !libsByAbi.length}<p class="dim">No native libraries.</p>{/if}
          </section>
        {/if}
      </div>
    {/if}
  </div>
</div>

<style>
  .page {
    flex: 1;
    min-height: 0;
    overflow: auto;
  }
  .in {
    max-width: 1100px;
    padding: 32px 40px 64px;
  }
  .ident {
    display: flex;
    align-items: center;
    gap: 18px;
    padding-bottom: 30px;
  }
  .appicon {
    width: 54px;
    height: 54px;
    flex: none;
    display: grid;
    place-items: center;
    border-radius: 14px;
    color: var(--ok);
    background: color-mix(in srgb, var(--ok) 15%, var(--panel));
  }
  .who {
    min-width: 0;
  }
  h1 {
    margin: 0 0 4px;
    font: 600 24px/1.2 var(--font-ui);
    letter-spacing: -0.01em;
    color: var(--text-hi);
    overflow-wrap: anywhere;
  }
  .pk {
    margin: 0;
    font: 13px var(--font-code);
    color: var(--text-2);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .facts {
    display: flex;
    gap: 30px;
    margin: 0 0 0 auto;
  }
  .facts div {
    display: flex;
    flex-direction: column;
    gap: 3px;
  }
  .facts dt {
    font-size: 12.5px;
    color: var(--text-3);
  }
  .facts dd {
    margin: 0;
    font: 600 15px var(--font-ui);
    color: var(--text);
    white-space: nowrap;
    font-variant-numeric: tabular-nums;
  }
  .wait {
    display: flex;
    align-items: center;
    gap: 10px;
    margin: 12px 0;
    color: var(--text-3);
  }

  .map {
    margin-bottom: 36px;
  }
  .map-head {
    display: flex;
    align-items: baseline;
    gap: 10px;
    margin-bottom: 12px;
  }
  .map-head h2 {
    margin: 0;
    padding: 0;
  }
  .map-head span {
    color: var(--text-3);
  }
  .bar {
    display: flex;
    gap: 3px;
    height: 26px;
  }
  .bar button {
    min-width: 6px;
    border-radius: 6px;
  }
  .bar button:hover:not(:disabled) {
    filter: brightness(1.12);
  }
  .bar button:disabled {
    cursor: default;
  }
  .legend {
    display: flex;
    flex-wrap: wrap;
    gap: 8px 24px;
    margin-top: 14px;
  }
  .legend button {
    display: flex;
    align-items: center;
    gap: 8px;
    color: var(--text-2);
  }
  .legend button:hover:not(:disabled) {
    color: var(--text-hi);
  }
  .legend button:disabled {
    cursor: default;
  }
  .legend i {
    width: 11px;
    height: 11px;
    border-radius: 3px;
  }
  .legend b {
    font-weight: 600;
    color: var(--text);
    font-variant-numeric: tabular-nums;
  }
  .libs {
    margin: 14px 0 0;
    color: var(--text-2);
    line-height: 1.7;
  }
  .libs > .dim:first-child {
    margin-right: 8px;
  }
  .lib {
    color: var(--text);
  }
  .lib:hover {
    color: var(--text-hi);
    text-decoration: underline;
    text-underline-offset: 3px;
  }

  .grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(400px, 1fr));
    gap: 34px 44px;
  }
  h2 {
    display: flex;
    align-items: baseline;
    gap: 8px;
    margin: 0 0 6px;
    padding-bottom: 8px;
    font: 600 15px var(--font-ui);
    color: var(--text-hi);
  }
  h2 .n {
    font-weight: 400;
    font-size: 13.5px;
    color: var(--text-3);
  }
  h2 .r {
    margin-left: auto;
    font-weight: 400;
    font-size: 12.5px;
    color: var(--text-3);
  }
  h3 {
    margin: 18px 0 6px;
    font: 600 13.5px var(--font-ui);
    color: var(--text-2);
  }
  h3 .n {
    font-weight: 400;
    color: var(--text-3);
  }
  .note {
    margin: 10px 0 0;
    font-size: 12.5px;
    color: var(--text-3);
  }
  .desc {
    margin: 0 0 12px;
    color: var(--text-2);
    line-height: 1.55;
  }
  .tr .detail {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .finding {
    display: flex;
    align-items: flex-start;
    gap: 12px;
    padding: 11px 0;
    border-top: 1px solid var(--line);
    color: var(--text-3);
  }
  .finding :global(svg) {
    margin-top: 2px;
  }
  .finding.error :global(svg) {
    color: var(--bad);
  }
  .finding.warn :global(svg) {
    color: var(--warn);
  }
  .finding.info :global(svg) {
    color: var(--accent);
  }
  .finding div {
    flex: 1;
    min-width: 0;
  }
  .finding b {
    font-weight: 600;
    color: var(--text);
  }
  .finding p {
    margin: 3px 0 0;
    color: var(--text-2);
  }
  .finding .more {
    margin: 2px 0 0;
    flex: none;
  }
  .tr {
    display: flex;
    align-items: baseline;
    gap: 12px;
    padding: 7px 0;
    border-top: 1px solid var(--line);
  }
  .tr .a {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--text-2);
  }
  .tr .a.hi {
    color: var(--text-hi);
  }
  .tr .b {
    margin-left: auto;
    flex: none;
    color: var(--text-3);
    white-space: nowrap;
  }
  .flag {
    flex: none;
    font-weight: 600;
    font-size: 12.5px;
    color: var(--text-3);
  }
  .flag.bad,
  .b.bad {
    color: var(--bad) !important;
  }
  .flag.warn,
  .b.warn,
  dd.warn {
    color: var(--warn) !important;
  }
  .kv {
    display: grid;
    grid-template-columns: 120px minmax(0, 1fr);
    margin: 0 0 12px;
  }
  .kv dt,
  .kv dd {
    padding: 7px 0;
    border-top: 1px solid var(--line);
  }
  .kv dt {
    color: var(--text-3);
  }
  .kv dd {
    margin: 0;
    min-width: 0;
    color: var(--text);
    overflow-wrap: anywhere;
  }
  .mono {
    font-family: var(--font-code);
    font-size: 13px;
  }
  .dim {
    color: var(--text-3);
  }
  /* Svelte trims the leading space inside these spans, so space them here. */
  dd .dim {
    margin-left: 0.45em;
  }
  p.dim {
    margin: 0;
    padding: 7px 0;
    border-top: 1px solid var(--line);
  }
  .hash {
    display: flex;
    align-items: flex-start;
    gap: 6px;
    font-size: 12px;
    line-height: 1.5;
    color: var(--text-2);
  }
  .hash .ib {
    width: 24px;
    height: 24px;
  }
  .libs2 {
    color: var(--text-2);
  }
  .cl {
    color: var(--text-hi);
    text-align: left;
    text-decoration: underline;
    text-decoration-color: var(--gutter);
    text-underline-offset: 3px;
    overflow-wrap: anywhere;
  }
  .cl:hover {
    text-decoration-color: var(--accent);
  }
  .more {
    margin-top: 6px;
    color: var(--text-2);
    text-decoration: underline;
    text-decoration-color: var(--gutter);
    text-underline-offset: 3px;
  }
  .more:hover {
    color: var(--text-hi);
  }
</style>
