// The class tree: packages and classes, flattened to the rows that are visible.
import type { ClassEntry } from "$lib/engine";

export interface Pkg {
  /** Shown name; middle packages with nothing of their own are merged, as in `com.example.app`. */
  label: string;
  /** `com/example/app`, the key for expanded state. */
  path: string;
  pkgs: Pkg[];
  classes: ClassEntry[];
  /** Classes in this package and below. */
  total: number;
}

export type Row =
  | { type: "pkg"; key: string; depth: number; pkg: Pkg; open: boolean }
  | { type: "cls"; key: string; depth: number; cls: ClassEntry };

export function buildTree(classes: ClassEntry[]): Pkg {
  const root: Pkg = { label: "", path: "", pkgs: [], classes: [], total: 0 };
  const byPath = new Map<string, Pkg>([["", root]]);
  const pkgFor = (path: string): Pkg => {
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
  const finish = (p: Pkg): number => {
    p.pkgs.sort((a, b) => a.label.localeCompare(b.label));
    p.total = p.classes.length + p.pkgs.reduce((n, sub) => n + finish(sub), 0);
    // Merge a package that only holds one package: com > example > app becomes com.example.app.
    p.pkgs = p.pkgs.map((sub) => {
      while (sub.classes.length === 0 && sub.pkgs.length === 1) {
        const only = sub.pkgs[0];
        sub = { ...only, label: `${sub.label}.${only.label}` };
      }
      return sub;
    });
    return p.total;
  };
  finish(root);
  return root;
}

export function visibleRows(root: Pkg, open: Set<string>): Row[] {
  const rows: Row[] = [];
  const walk = (p: Pkg, depth: number) => {
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
export function pathsTo(root: Pkg, classId: string): string[] {
  const out: string[] = [];
  const pkg = classId.includes("/") ? classId.slice(0, classId.lastIndexOf("/")) : "";
  let p = root;
  for (;;) {
    const next = p.pkgs.find((s) => pkg === s.path || pkg.startsWith(s.path + "/"));
    if (!next) break;
    out.push(next.path);
    p = next;
  }
  return out;
}
