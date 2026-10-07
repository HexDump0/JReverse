// The class tree: packages and classes, flattened to the rows that are visible.
// The Files panel uses it too, with folders and files.
import type { ClassEntry } from "$lib/engine";

export interface Pkg<T extends { id: string } = ClassEntry> {
  /** Shown name; middle packages with nothing of their own are merged, as in `com.example.app`. */
  label: string;
  /** `com/example/app`, the key for expanded state. */
  path: string;
  pkgs: Pkg<T>[];
  classes: T[];
  /** Classes in this package and below. */
  total: number;
  /** The group holding every known library, see `buildClassTree`. */
  lib?: boolean;
  /** Most of the classes right in it have obfuscated names. */
  obf?: boolean;
}

/** The path of the Libraries group; no real package can have it. */
export const LIBRARIES = "\u0000libraries";

export type Row<T extends { id: string } = ClassEntry> =
  | { type: "pkg"; key: string; depth: number; pkg: Pkg<T>; open: boolean }
  | { type: "cls"; key: string; depth: number; cls: T };

/** `join` glues merged middle packages: `com.example.app`, or `res/values` for folders. */
export function buildTree<T extends { id: string }>(classes: T[], join = "."): Pkg<T> {
  const root: Pkg<T> = { label: "", path: "", pkgs: [], classes: [], total: 0 };
  const byPath = new Map<string, Pkg<T>>([["", root]]);
  const pkgFor = (path: string): Pkg<T> => {
    let p = byPath.get(path);
    if (p) return p;
    const i = path.lastIndexOf("/");
    const parent = pkgFor(i < 0 ? "" : path.slice(0, i));
    p = { label: path.slice(i + 1), path, pkgs: [], classes: [], total: 0 };
    parent.pkgs.push(p);
    byPath.set(path, p);
    return p;
  };
  for (const c of classes) {
    const i = c.id.lastIndexOf("/");
    pkgFor(i < 0 ? "" : c.id.slice(0, i)).classes.push(c);
  }
  const finish = (p: Pkg<T>): number => {
    p.pkgs.sort((a, b) => a.label.localeCompare(b.label));
    p.classes.sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
    p.total = p.classes.length + p.pkgs.reduce((n, sub) => n + finish(sub), 0);
    // Merge a package that only holds one package: com > example > app becomes com.example.app.
    p.pkgs = p.pkgs.map((sub) => {
      while (sub.classes.length === 0 && sub.pkgs.length === 1) {
        const only = sub.pkgs[0];
        sub = { ...only, label: `${sub.label}${join}${only.label}` };
      }
      return sub;
    });
    return p.total;
  };
  finish(root);
  return root;
}

/**
 * The class tree: the app's own packages first, then every known library under
 * one Libraries group, so your code isn't buried under androidx and kotlin.
 * A file that is all library (a library's own JAR) isn't grouped.
 */
export function buildClassTree<T extends { id: string }>(classes: T[], isLibrary: (id: string) => boolean, isObfuscated: (id: string) => boolean): Pkg<T> {
  const app = classes.filter((c) => !isLibrary(c.id));
  const libs = app.length ? classes.filter((c) => isLibrary(c.id)) : [];
  const root = buildTree(libs.length ? app : classes);
  if (libs.length) {
    const lib = buildTree(libs);
    root.pkgs.push({ label: "Libraries", path: LIBRARIES, pkgs: lib.pkgs, classes: lib.classes, total: lib.total, lib: true });
    root.total += lib.total;
  }
  const mark = (p: Pkg<T>) => {
    const obf = p.classes.filter((c) => isObfuscated(c.id)).length;
    if (p.classes.length >= 3 && obf / p.classes.length >= 0.6) p.obf = true;
    p.pkgs.forEach(mark);
  };
  root.pkgs.forEach(mark);
  return root;
}

export function visibleRows<T extends { id: string }>(root: Pkg<T>, open: Set<string>): Row<T>[] {
  const rows: Row<T>[] = [];
  const walk = (p: Pkg<T>, depth: number) => {
    for (const sub of p.pkgs) {
      const isOpen = open.has(sub.path);
      rows.push({ type: "pkg", key: sub.path, depth, pkg: sub, open: isOpen });
      if (isOpen) walk(sub, depth + 1);
    }
    for (const c of p.classes) rows.push({ type: "cls", key: c.id, depth, cls: c });
  };
  walk(root, 0);
  return rows;
}

/** Every package path that must be open to show `classId`. */
export function pathsTo<T extends { id: string }>(root: Pkg<T>, classId: string): string[] {
  const out: string[] = [];
  const pkg = classId.includes("/") ? classId.slice(0, classId.lastIndexOf("/")) : "";
  const holds = (s: Pkg<T>) => pkg === s.path || pkg.startsWith(s.path + "/");
  let p = root;
  for (;;) {
    let next = p.pkgs.find((s) => !s.lib && holds(s));
    if (!next) {
      // Into the Libraries group, if the class is in there.
      const lib = p.pkgs.find((s) => s.lib);
      if (lib && (lib.pkgs.some(holds) || lib.classes.some((c) => c.id === classId))) next = lib;
    }
    if (!next) break;
    out.push(next.path);
    p = next;
  }
  return out;
}
