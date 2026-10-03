// Typed wrappers around the engine commands in src-tauri/src/commands.rs.
import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";

export type InputKind = "apk" | "aab" | "aar" | "jar" | "dex" | "class";
export type ClassKind = "class" | "interface" | "enum" | "annotation" | "record";

export interface Opened {
  /** Stable across engine restarts. */
  session: string;
  kind: InputKind;
  classCount: number;
  /** Decompilers that can read this file, jadx first. */
  engines: string[];
  /** Whether jadx generated names for short and clashing identifiers. */
  deobfuscated: boolean;
  ms: number;
}

export interface ClassEntry {
  /** Original internal name, e.g. `com/foo/Bar`. Never changes on rename. */
  id: string;
  kind: ClassKind;
  /** The name jadx shows when it isn't the id's, e.g. a generated `C0123a`. */
  name?: string;
}

export interface Decompiled {
  source: string;
  engine: string;
  ms: number;
  warnings: number;
  /** Identifiers that name a node: flat `[line, col, len, node]` quadruples, 0-based. */
  links: number[];
  /** The declarations among `links`, same layout. */
  decls: number[];
  nodes: NodeInfo[];
}

export type NodeKind = "class" | "method" | "field";

/** A class, method or field. See engine/README.md. */
export interface NodeInfo {
  kind: NodeKind;
  /** `com/foo/Bar`, `com/foo/Bar.run(I)V` or `com/foo/Bar.count:I`. Never changes on rename. */
  id: string;
  /** The top-level class whose source declares it. */
  top: string;
  name: string;
  /** e.g. `run(int): void`. */
  detail: string;
  access: "public" | "protected" | "private" | "";
  static: boolean;
  /** Argument types for Frida's `overload()`, methods only. */
  frida?: string[];
}

export interface Smali {
  source: string;
  ms: number;
}

export interface Usage {
  cls: string;
  line: number;
  col: number;
  len: number;
  text: string;
  in?: NodeInfo;
}

export type SearchScope = "classes" | "members" | "code" | "strings" | "files";

export interface NameHit {
  type: "class" | "method" | "field";
  cls: string;
  node: NodeInfo;
}

export interface CodeHit {
  /** `string` when the match is inside a string literal. */
  type: "code" | "string";
  cls: string;
  line: number;
  col: number;
  len: number;
  text: string;
}

/** A line in a resource or other text file. */
export interface FileHit {
  type: "file";
  path: string;
  line: number;
  col: number;
  len: number;
  text: string;
}

export type SearchHit = NameHit | CodeHit | FileHit;

export const isNameHit = (h: SearchHit): h is NameHit => "node" in h;
export const isFileHit = (h: SearchHit): h is FileHit => h.type === "file";

export interface SearchResult {
  hits: SearchHit[];
  truncated: boolean;
  searched: number;
  ms: number;
}

export interface Progress {
  ticket: string;
  done: number;
  total: number;
}

export interface Cert {
  subject: string;
  issuer: string;
  serial: string;
  notBefore: string;
  notAfter: string;
  algorithm: string;
  key: string;
  sha256: string;
  sha1: string;
  debug: boolean;
}

export interface Component {
  type: "activity" | "service" | "receiver" | "provider";
  name: string;
  alias?: string;
  permission?: string;
  authorities?: string;
  actions: string[];
  links: string[];
  launcher: boolean;
  exported: boolean;
  exportedImplicitly: boolean;
}

export interface AndroidInfo {
  package: string;
  versionName?: string;
  versionCode?: string;
  compileSdk?: string;
  minSdk?: string;
  targetSdk?: string;
  label?: string;
  application?: string;
  debuggable?: string;
  allowBackup?: string;
  usesCleartextTraffic?: string;
  networkSecurityConfig?: string;
  extractNativeLibs?: string;
  permissions: { name: string; maxSdk?: string }[];
  declaredPermissions: { name: string; protectionLevel?: string }[];
  features: string[];
  components: Component[];
}

export interface Overview {
  path: string;
  kind: InputKind;
  size: number;
  classes: number;
  methods: number;
  fields: number;
  files?: number;
  dex?: { name: string; size: number }[];
  nativeLibs?: { abi: string; name: string; size: number }[];
  javaVersions?: { java: string; classes: number }[];
  jarManifest?: Record<string, string>;
  signing?: { schemes: string[]; certs: Cert[] };
  manifest?: string;
  android?: AndroidInfo;
}

export interface ExportResult {
  dir: string;
  written: number;
  failed: number;
  ms: number;
}

/** What the user added to a file, saved per file by src-tauri/src/projects.rs. */
export interface Project {
  renames: Record<string, string>;
  comments: Record<string, string>;
  bookmarks: { cls: string; line: number; note: string }[];
  /** Open the file with jadx's generated names. */
  deobfuscate?: boolean;
  /** Open tabs when the file was last closed, to resume. */
  tabs?: { cls: string; view: "java" | "vineflower" | "smali"; line: number }[];
  active?: string;
}

/** What every command rejects with. */
export interface EngineError {
  code: string;
  message: string;
}

export type EngineStatus =
  | { state: "starting"; generation: number }
  | { state: "ready"; generation: number; version: string; engines: string[] }
  | { state: "stopped"; generation: number }
  | { state: "crashed"; generation: number }
  | { state: "failed"; code: string; message: string };

/** `deobfuscate` has jadx give short and clashing names generated aliases. */
export function openFile(path: string, deobfuscate = false): Promise<Opened> {
  return invoke("open_file", { path, deobfuscate });
}

export function listClasses(session: string): Promise<ClassEntry[]> {
  return invoke("list_classes", { session });
}

export function decompileClass(session: string, classId: string, decompiler?: string): Promise<Decompiled> {
  return invoke("decompile_class", { session, classId, decompiler });
}

export function smaliClass(session: string, classId: string): Promise<Smali> {
  return invoke("smali_class", { session, classId });
}

export function nodeInfo(session: string, node: string): Promise<NodeInfo> {
  return invoke("node_info", { session, node });
}

export function findUsages(session: string, node: string): Promise<{ usages: Usage[]; ms: number }> {
  return invoke("find_usages", { session, node });
}

export interface SearchOptions {
  query: string;
  regex: boolean;
  caseSensitive: boolean;
  scopes: SearchScope[];
  limit?: number;
  /** Lets `cancelJob` stop it, and tags its progress events. */
  ticket?: string;
}

export function search(session: string, o: SearchOptions): Promise<SearchResult> {
  return invoke("search", { session, ...o });
}

export function exportSources(session: string, dir: string, ticket?: string): Promise<ExportResult> {
  return invoke("export_sources", { session, dir, ticket });
}

export function cancelJob(ticket: string): Promise<void> {
  return invoke("cancel_job", { ticket });
}

export function overview(session: string): Promise<Overview> {
  return invoke("overview", { session });
}

export function setCodeData(session: string, renames: Record<string, string>, comments: Record<string, string>): Promise<{ applied: number }> {
  return invoke("set_code_data", { session, renames, comments });
}

export interface FileEntry {
  path: string;
  type: "manifest" | "arsc" | "xml" | "image" | "lib" | "font" | "archive" | "text" | "file";
  /** Bytes, or -1 when unknown (files decoded out of resources.arsc). */
  size: number;
}

export interface FileContent {
  path: string;
  kind: "text" | "image" | "binary" | "table";
  size: number;
  text?: string;
  /** Base64: the image, or the first 64 KB of a binary file. */
  data?: string;
  mime?: string;
  /** For resources.arsc: the files it decodes to. */
  children?: string[];
  truncated: boolean;
}

export function listFiles(session: string): Promise<{ files: FileEntry[] }> {
  return invoke("list_files", { session });
}

export function readFile(session: string, path: string): Promise<FileContent> {
  return invoke("read_file", { session, path });
}

export function loadProject(path: string): Promise<Project | null> {
  return invoke("load_project", { path });
}

/** Null deletes the saved project. */
export function saveProject(path: string, data: Project | null): Promise<void> {
  return invoke("save_project", { path, data });
}

export function writeTextFile(path: string, contents: string): Promise<void> {
  return invoke("write_text_file", { path, contents });
}

export function onEngineProgress(handler: (p: Progress) => void): Promise<UnlistenFn> {
  return listen<Progress>("engine://progress", (e) => handler(e.payload));
}

export function closeSession(session: string): Promise<void> {
  return invoke("close_session", { session });
}

/** One line of the engine's stderr. */
export function onEngineLog(handler: (line: string) => void): Promise<UnlistenFn> {
  return listen<string>("engine://log", (e) => handler(e.payload));
}

export function onEngineStatus(handler: (status: EngineStatus) => void): Promise<UnlistenFn> {
  return listen<EngineStatus>("engine://status", (e) => handler(e.payload));
}

export function errorMessage(e: unknown): string {
  if (typeof e === "object" && e !== null && "message" in e) {
    return String((e as EngineError).message);
  }
  return String(e);
}

/** First bytes of a file and what it is, without opening it. See src-tauri/src/peek.rs. */
export interface Peek {
  name: string;
  size: number;
  /** Up to the first 16 bytes. */
  head: number[];
  kind: InputKind | null;
  /** e.g. "3 DEX files", "Java 21". */
  detail: string | null;
  classes: number | null;
  problem: { title: string; text: string } | null;
}

export interface Recent {
  path: string;
  kind: InputKind;
  classCount: number;
  size: number;
  /** Unix time in milliseconds. */
  openedAt: number;
}

export interface RecentView extends Recent {
  /** The file isn't at `path` any more. */
  missing: boolean;
}

export function peekFile(path: string): Promise<Peek> {
  return invoke("peek_file", { path });
}

export function recentFiles(): Promise<RecentView[]> {
  return invoke("recent_files");
}

export function forgetRecent(path: string): Promise<RecentView[]> {
  return invoke("forget_recent", { path });
}

/** Replaces the whole list, e.g. to undo a remove. */
export function replaceRecents(entries: Recent[]): Promise<RecentView[]> {
  return invoke("replace_recents", { entries });
}

/** The example app bundled with JReverse, or null if this build has none. */
export function exampleFile(): Promise<string | null> {
  return invoke("example_file");
}
