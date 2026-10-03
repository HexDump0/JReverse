<script lang="ts">
  // The first tab: what this file is, what stands out, and where to start reading.
  import type { Cert, Component } from "$lib/engine";
  import Icon from "$lib/Icon.svelte";
  import Dots from "$lib/Dots.svelte";
  import { fmtN, fmtSize, kindLabel, tildify } from "$lib/format";
  import { say } from "$lib/status.svelte";
  import { androidVersion, groupClasses, permissionLabel, permissionLevel, type PermissionLevel } from "./android";
  import { dotted, type Workspace } from "./workspace.svelte";

  interface Props {
    ws: Workspace;
    home: string | null;
    /** Narrows the class tree to a package. */
    onfilter: (prefix: string) => void;
  }

  let { ws, home, onfilter }: Props = $props();

  const COMPONENTS_SHOWN = 8;
  const GROUPS_SHOWN = 10;

  let allComponents = $state(false);
  let allPermissions = $state(false);
  let allGroups = $state(false);
  let allLinks = $state(false);

  const o = $derived(ws.info);
  const a = $derived(o?.android);
  const makeup = $derived(groupClasses(ws.classes, a?.package));
  const biggest = $derived(makeup.groups[0]?.classes ?? 1);
  const libraryShare = $derived(makeup.groups.filter((g) => g.library).reduce((n, g) => n + g.classes, 0) / Math.max(1, ws.classes.length));

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
    if (share >= 0.15) f.push({ level: "info", title: "Likely obfuscated", text: `${Math.round(share * 100)}% of classes have one- or two-letter names.` });
    return f;
  });

  const mainClass = $derived(o?.jarManifest?.["Main-Class"] ?? o?.jarManifest?.["Start-Class"]);

  const classId = (javaName: string | undefined) => (javaName ? javaName.replaceAll(".", "/") : "");
  const has = (javaName: string | undefined) => !!javaName && ws.byId.has(classId(javaName));

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

<div class="page selectable">
  <div class="in">
    <header>
      <h1>{a?.label && !a.label.startsWith("@") ? a.label : ws.name}</h1>
      <p class="path" title={ws.path}>{tildify(ws.path, home)}</p>
      <p class="facts">
        <Dots
          parts={[
            kindLabel(ws.opened.kind),
            o && fmtSize(o.size),
            `${fmtN(ws.classes.length)} classes`,
            o && `${fmtN(o.methods)} methods`,
            o && `${fmtN(o.fields)} fields`,
          ]}
        />
      </p>
    </header>

    {#if !o}
      <p class="wait">
        {#if ws.infoError}Couldn't read the file's details: {ws.infoError}{:else}<i class="spin"></i>Reading the file{/if}
      </p>
    {:else}
      {#if findings.length}
        <section class="findings" aria-label="Worth a look">
          {#each findings as f (f.title)}
            <div class="finding {f.level}">
              <Icon name="alert" size={15} />
              <div><strong>{f.title}</strong><span>{f.text}</span></div>
            </div>
          {/each}
        </section>
      {/if}

      <div class="grid">
        {#if a}
          <section>
            <h2>App</h2>
            <dl>
              <dt>Package</dt>
              <dd class="mono">{a.package}</dd>
              {#if a.versionName || a.versionCode}
                <dt>Version</dt>
                <dd>{a.versionName ?? ""}{#if a.versionCode}<span class="dim"> build {a.versionCode}</span>{/if}</dd>
              {/if}
              {#each [["Min SDK", a.minSdk], ["Target SDK", a.targetSdk], ["Compile SDK", a.compileSdk]] as [label, sdk] (label)}
                {#if sdk}
                  <dt>{label}</dt>
                  <dd>{sdk}{#if androidVersion(sdk)}<span class="dim"> {androidVersion(sdk)}</span>{/if}</dd>
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
              <button class="act" onclick={() => ws.showManifest()}>Open AndroidManifest.xml</button>
            {/if}
          </section>
        {/if}

        {#if o.jarManifest || o.javaVersions?.length}
          <section>
            <h2>Java</h2>
            <dl>
              {#if mainClass}
                <dt>Main class</dt>
                <dd class="mono">{#if has(mainClass)}<button class="cl" onclick={() => open(mainClass)}>{mainClass}</button>{:else}{mainClass}{/if}</dd>
              {/if}
              {#each ["Premain-Class", "Agent-Class", "Launcher-Agent-Class", "Plugin-Class"] as key (key)}
                {#if o.jarManifest?.[key]}
                  <dt>{key.replace("-Class", "").replace("-", " ")}</dt>
                  <dd class="mono">{#if has(o.jarManifest[key])}<button class="cl" onclick={() => open(o.jarManifest?.[key])}>{o.jarManifest[key]}</button>{:else}{o.jarManifest[key]}{/if}</dd>
                {/if}
              {/each}
              {#if o.javaVersions?.length}
                <dt>Bytecode</dt>
                <dd>
                  {#each o.javaVersions as v, i (v.java)}{#if i}, {/if}Java {v.java}{#if o.javaVersions.length > 1}<span class="dim"> {fmtN(v.classes)}</span>{/if}{/each}
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
            <h2>Exported components <span class="n">{components.length}</span></h2>
            {#if components.length}
              <ul class="comps">
                {#each allComponents ? components : components.slice(0, COMPONENTS_SHOWN) as c (c.type + c.name + (c.alias ?? ""))}
                  <li>
                    <span class="ctype">{typeLabel[c.type]}</span>
                    <span class="cname mono">
                      {#if has(c.name)}<button class="cl" onclick={() => open(c.name)}>{short(c.name)}</button>{:else}{short(c.name)}{/if}
                    </span>
                    <span class="tags">
                      {#if c.launcher}<span class="tag">launcher</span>{/if}
                      {#if c.exportedImplicitly}<span class="tag warn">implicit</span>{/if}
                      {#if c.permission}<span class="tag" title={c.permission}>needs permission</span>{/if}
                    </span>
                  </li>
                {/each}
              </ul>
              {#if components.length > COMPONENTS_SHOWN}
                <button class="more" onclick={() => (allComponents = !allComponents)}>{allComponents ? "Show fewer" : `Show all ${components.length}`}</button>
              {/if}
            {:else}
              <p class="dim">Nothing is exported.</p>
            {/if}
            {#if deepLinks.length}
              <h3>Deep links <span class="n">{deepLinks.length}</span></h3>
              <ul class="links mono">
                {#each allLinks ? deepLinks : deepLinks.slice(0, 8) as l (l)}<li>{l}</li>{/each}
              </ul>
              {#if deepLinks.length > 8}
                <button class="more" onclick={() => (allLinks = !allLinks)}>{allLinks ? "Show fewer" : `Show all ${deepLinks.length}`}</button>
              {/if}
            {/if}
          </section>

          <section>
            <h2>
              Permissions <span class="n">{permissions.length}</span>
              {#if dangerousCount}<span class="sub">{dangerousCount} dangerous</span>{/if}
            </h2>
            {#if permissions.length}
              <ul class="perms">
                {#each allPermissions ? permissions : permissions.slice(0, 12) as p (p.name)}
                  <li class={p.level}>
                    <span class="mono pname" title={p.name}>{permissionLabel(p.name)}</span>
                    {#if p.level === "dangerous" || p.level === "special"}<span class="lvl">{p.level}</span>{/if}
                    {#if p.maxSdk}<span class="dim">up to SDK {p.maxSdk}</span>{/if}
                  </li>
                {/each}
              </ul>
              {#if permissions.length > 12}
                <button class="more" onclick={() => (allPermissions = !allPermissions)}>{allPermissions ? "Show fewer" : `Show all ${permissions.length}`}</button>
              {/if}
            {:else}
              <p class="dim">None requested.</p>
            {/if}
          </section>
        {/if}

        <section class="wide">
          <h2>Code {#if libraryShare >= 0.01}<span class="sub">{Math.round(libraryShare * 100)}% known libraries</span>{/if}</h2>
          <div class="groups">
            {#each allGroups ? makeup.groups : makeup.groups.slice(0, GROUPS_SHOWN) as g (g.name)}
              <button class="group" onclick={() => g.prefix && onfilter(dotted(g.prefix))} title={g.prefix ? `Show ${dotted(g.prefix)} in the class list` : ""}>
                <span class="gname" class:lib={g.library}>{g.name}</span>
                <span class="gkind">{g.library ? "library" : ""}</span>
                <span class="bar"><i class:lib={g.library} style:width="{Math.max(1, (g.classes / biggest) * 100)}%"></i></span>
                <span class="gn">{fmtN(g.classes)}</span>
              </button>
            {/each}
          </div>
          {#if makeup.groups.length > GROUPS_SHOWN}
            <button class="more" onclick={() => (allGroups = !allGroups)}>{allGroups ? "Show fewer" : `Show all ${makeup.groups.length} groups`}</button>
          {/if}
        </section>

        {#if o.signing && (o.signing.schemes.length || o.kind === "apk")}
          <section>
            <h2>Signing {#if o.signing.schemes.length}<span class="sub">{o.signing.schemes.join(", ")}</span>{/if}</h2>
            {#if !certs.length}
              <p class="dim">Not signed.</p>
            {/if}
            {#each certs as c (c.sha256)}
              <dl>
                <dt>Signer</dt>
                <dd title={c.subject}>{cn(c.subject)}</dd>
                {#if c.issuer !== c.subject}
                  <dt>Issuer</dt>
                  <dd title={c.issuer}>{cn(c.issuer)}</dd>
                {/if}
                <dt>Valid</dt>
                <dd class:expired={expired(c)}>{date(c.notBefore)} to {date(c.notAfter)}{#if expired(c)} (expired){/if}</dd>
                <dt>Key</dt>
                <dd>{c.key}<span class="dim"> {c.algorithm}</span></dd>
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
            <h2>Contents {#if o.files}<span class="sub">{fmtN(o.files)} files</span>{/if}</h2>
            <dl>
              {#if o.dex?.length}
                <dt>DEX</dt>
                <dd>{o.dex.length === 1 ? "1 file" : `${o.dex.length} files`}<span class="dim"> {fmtSize(o.dex.reduce((n, d) => n + d.size, 0))}</span></dd>
              {/if}
              {#each libsByAbi as [abi, names] (abi)}
                <dt class="mono">{abi}</dt>
                <dd class="mono libs">{names.join(", ")}</dd>
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
    background: var(--pane);
  }
  .in {
    max-width: 1080px;
    padding: 40px 48px 64px;
  }
  header h1 {
    margin: 0;
    font: 600 26px/1.15 var(--font-ui);
    letter-spacing: -0.02em;
    color: var(--text-hi);
    overflow-wrap: anywhere;
  }
  .path {
    margin: 6px 0 0;
    font: 12px var(--font-code);
    color: var(--text-3);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .facts {
    margin: 10px 0 0;
    font-size: 13px;
    color: var(--text-2);
  }
  .wait {
    display: flex;
    align-items: center;
    gap: 10px;
    margin: 32px 0;
    color: var(--text-3);
  }

  .findings {
    display: flex;
    flex-direction: column;
    gap: 2px;
    margin-top: 28px;
  }
  .finding {
    display: flex;
    gap: 12px;
    align-items: flex-start;
    padding: 10px 14px;
    border-radius: 8px;
    background: var(--shelf);
    color: var(--text-3);
  }
  .finding :global(svg) {
    margin-top: 2px;
  }
  .finding div {
    display: flex;
    flex-wrap: wrap;
    gap: 2px 12px;
  }
  .finding strong {
    font-weight: 600;
  }
  .finding span {
    color: var(--text-2);
  }
  .finding.error {
    color: var(--error);
  }
  .finding.warn {
    color: var(--obf);
  }
  .finding.info strong {
    color: var(--text-hi);
  }

  .grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(420px, 1fr));
    gap: 36px 56px;
    margin-top: 36px;
  }
  section.wide {
    grid-column: 1 / -1;
  }
  h2 {
    display: flex;
    align-items: baseline;
    gap: 10px;
    margin: 0 0 12px;
    padding-bottom: 8px;
    border-bottom: 1px solid var(--line);
    font: 600 14px var(--font-ui);
    color: var(--text-hi);
  }
  h3 {
    margin: 20px 0 8px;
    font: 600 12.5px var(--font-ui);
    color: var(--text-2);
  }
  h2 .n,
  h3 .n {
    font: 12px var(--font-code);
    color: var(--text-3);
  }
  h2 .sub {
    font: 400 12.5px var(--font-ui);
    color: var(--text-3);
  }
  dl {
    display: grid;
    grid-template-columns: 120px minmax(0, 1fr);
    gap: 7px 16px;
    margin: 0 0 14px;
    font-size: 13px;
  }
  dt {
    color: var(--text-3);
  }
  dd {
    margin: 0;
    color: var(--text);
    min-width: 0;
    overflow-wrap: anywhere;
  }
  .mono {
    font-family: var(--font-code);
    font-size: 12.5px;
  }
  .dim {
    color: var(--text-3);
  }
  /* Svelte trims the leading space inside these spans, so space them here. */
  dd .dim {
    margin-left: 0.4em;
  }
  p.dim {
    margin: 0;
    font-size: 13px;
  }
  .expired {
    color: var(--obf);
  }
  .hash {
    display: flex;
    align-items: flex-start;
    gap: 6px;
    font-size: 11.5px;
    line-height: 1.5;
    color: var(--text-2);
  }
  .libs {
    color: var(--text-2);
  }
  .cl {
    color: var(--text-hi);
    text-align: left;
    text-decoration: underline;
    text-decoration-color: #4b5156;
    text-underline-offset: 3px;
    overflow-wrap: anywhere;
  }
  .cl:hover {
    text-decoration-color: var(--accent);
  }
  .act,
  .more {
    margin-top: 4px;
    font-size: 12.5px;
    color: var(--text-2);
    text-decoration: underline;
    text-decoration-color: #3c4246;
    text-underline-offset: 3px;
  }
  .act:hover,
  .more:hover {
    color: var(--text-hi);
  }
  .ib {
    flex: none;
    display: grid;
    place-items: center;
    width: 22px;
    height: 22px;
    border-radius: 5px;
    color: var(--text-3);
  }
  .ib:hover {
    background: var(--lift-2);
    color: var(--text-hi);
  }

  ul {
    margin: 0;
    padding: 0;
    list-style: none;
  }
  .comps li {
    display: grid;
    grid-template-columns: 70px minmax(0, 1fr) auto;
    gap: 12px;
    align-items: baseline;
    padding: 4px 0;
    font-size: 13px;
  }
  .ctype {
    color: var(--text-3);
    font-size: 12px;
  }
  .cname {
    min-width: 0;
    overflow-wrap: anywhere;
  }
  .tags {
    display: flex;
    gap: 6px;
  }
  .tag {
    font-size: 11.5px;
    color: var(--text-3);
  }
  .tag.warn {
    color: var(--obf);
  }
  .links li {
    padding: 2px 0;
    color: var(--text-2);
    overflow-wrap: anywhere;
  }
  .perms li {
    display: flex;
    align-items: baseline;
    gap: 10px;
    padding: 3px 0;
    font-size: 13px;
  }
  .pname {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--text-2);
  }
  .perms .dangerous .pname,
  .perms .special .pname {
    color: var(--text-hi);
  }
  .lvl {
    font-size: 11.5px;
  }
  .dangerous .lvl {
    color: var(--obf);
  }
  .special .lvl {
    color: var(--error);
  }

  .groups {
    display: flex;
    flex-direction: column;
  }
  .group {
    display: grid;
    grid-template-columns: minmax(180px, 300px) 64px minmax(0, 1fr) 64px;
    gap: 16px;
    align-items: center;
    padding: 5px 8px;
    margin: 0 -8px;
    border-radius: 6px;
    text-align: left;
    font-size: 13px;
  }
  .group:hover {
    background: var(--shelf);
  }
  .gname {
    font-family: var(--font-code);
    font-size: 12.5px;
    color: var(--text-hi);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .gname.lib {
    font-family: var(--font-ui);
    font-size: 13px;
    color: var(--text-2);
  }
  .gkind {
    font-size: 11.5px;
    color: var(--text-3);
  }
  .bar {
    height: 6px;
    border-radius: 3px;
    background: var(--line);
    overflow: hidden;
  }
  .bar i {
    display: block;
    height: 100%;
    border-radius: 3px;
    background: var(--c-type);
  }
  .bar i.lib {
    background: var(--gutter);
  }
  .gn {
    text-align: right;
    font: 12px var(--font-code);
    color: var(--text-2);
  }
</style>
