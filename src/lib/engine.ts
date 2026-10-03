// Typed wrappers around the engine commands in src-tauri/src/commands.rs.
import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";

export type InputKind = "apk" | "aar" | "jar" | "dex" | "class";
export type ClassKind = "class" | "interface" | "enum" | "annotation" | "record";

export interface Opened {
  /** Stable across engine restarts. */
  session: string;
  kind: InputKind;
  classCount: number;
  ms: number;
}

export interface ClassEntry {
  /** Original internal name, e.g. `com/foo/Bar`. Never changes on rename. */
  id: string;
  kind: ClassKind;
}

export interface Decompiled {
  source: string;
  engine: string;
  ms: number;
  warnings: number;
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

export function openFile(path: string): Promise<Opened> {
  return invoke("open_file", { path });
}

export function listClasses(session: string): Promise<ClassEntry[]> {
  return invoke("list_classes", { session });
}

export function decompileClass(session: string, classId: string, decompiler?: string): Promise<Decompiled> {
  return invoke("decompile_class", { session, classId, decompiler });
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
