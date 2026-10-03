// A source text ready for the code view: lines, colour tokens, links per line
// and where each class, method and field is declared.
import type { Decompiled, NodeInfo } from "$lib/engine";
import { highlight, type Token, type TokenKind } from "$lib/java/highlight";
import { highlightSmali } from "$lib/java/smali";
import { highlightXml } from "$lib/java/xml";

export type View = "java" | "smali";

export interface Link {
  col: number;
  len: number;
  /** Index into `Doc.nodes`. */
  node: number;
  decl: boolean;
}

export interface Pos {
  line: number;
  col: number;
}

export interface Doc {
  lines: string[];
  tokens: Token[][];
  links: Link[][];
  nodes: NodeInfo[];
  /** Node id to where it's declared in this text. */
  decls: Map<string, Pos>;
  /** Declarations in line order, for "what am I inside of". */
  declLines: { line: number; node: number }[];
  /** The widest line, in characters, for horizontal scrolling. */
  width: number;
  warnings: number;
  ms: number;
  engine: string;
}

function base(source: string, tokens: Token[][]): Omit<Doc, "warnings" | "ms" | "engine"> {
  const lines = source.split("\n").map((l) => (l.endsWith("\r") ? l.slice(0, -1) : l));
  return {
    lines,
    tokens,
    links: lines.map(() => []),
    nodes: [],
    decls: new Map(),
    declLines: [],
    width: lines.reduce((w, l) => Math.max(w, l.length), 0),
  };
}

export function javaDoc(d: Decompiled): Doc {
  const doc: Doc = { ...base(d.source, highlight(d.source)), nodes: d.nodes, warnings: d.warnings, ms: d.ms, engine: d.engine };
  const decl = new Set<string>();
  for (let i = 0; i < d.decls.length; i += 4) decl.add(`${d.decls[i]}:${d.decls[i + 1]}`);
  for (let i = 0; i < d.links.length; i += 4) {
    const [line, col, len, node] = [d.links[i], d.links[i + 1], d.links[i + 2], d.links[i + 3]];
    if (!doc.links[line]) continue;
    const isDecl = decl.has(`${line}:${col}`);
    doc.links[line].push({ col, len, node, decl: isDecl });
    if (isDecl) {
      const id = d.nodes[node].id;
      if (!doc.decls.has(id)) doc.decls.set(id, { line, col });
      doc.declLines.push({ line, node });
    }
  }
  for (const l of doc.links) l.sort((a, b) => a.col - b.col);
  doc.declLines.sort((a, b) => a.line - b.line);
  return doc;
}

const SMALI_REF = /\[*L([\w$/]+);(?:->([\w$<>]+)(\([^)]*\)[^\s,]+|:\S+))?/g;
// jadx's JVM bytecode listing: `invokevirtual com/foo/Bar run (I)V`, `getfield com/foo/Bar count I`, `new com/foo/Bar`.
const JVM_MEMBER = /\b(?:invoke\w+|[gp]et(?:static|field))\s+([\w$/]+)\s+([\w$<>]+)\s+(\S+)/;
const JVM_CLASS = /\b(?:new|checkcast|instanceof|anewarray)\s+([\w$/]+)\s*$/;

/**
 * Smali, or jadx's JVM bytecode listing for class files. References such as
 * `Lcom/foo/Bar;->run(I)V` spell the engine's node ids directly, so they become
 * links; `known` says which classes are in the input.
 */
export function smaliDoc(cls: string, source: string, ms: number, known: (id: string) => boolean): Doc {
  const doc: Doc = { ...base(source, highlightSmali(source)), warnings: 0, ms, engine: "smali" };
  const index = new Map<string, number>();
  const node = (id: string, kind: NodeInfo["kind"], name: string): number => {
    let n = index.get(id);
    if (n === undefined) {
      n = doc.nodes.length;
      index.set(id, n);
      // `top` is resolved through the engine when someone follows the link.
      doc.nodes.push({ kind, id, top: "", name, detail: name, access: "", static: false });
    }
    return n;
  };
  doc.lines.forEach((text, line) => {
    const t = text.trimStart();
    const indent = text.length - t.length;
    // Declarations: `.class ... Lcom/foo/Bar;` (smali) or `.class ... com/foo/Bar` (bytecode),
    // `.method <flags> name(desc)ret`, `.field <flags> name:type` or `name type`.
    let declared: { id: string; kind: NodeInfo["kind"]; name: string; at: number } | null = null;
    if (t.startsWith(".method ")) {
      const m = /([\w$<>]+)(\([^)]*\)\S+)\s*$/.exec(t);
      if (m) declared = { id: `${cls}.${m[1]}${m[2]}`, kind: "method", name: m[1], at: indent + m.index };
    } else if (t.startsWith(".field ")) {
      const m = /([\w$]+)(?::| )(\[*(?:L[\w$/]+;|[ZBSCIJFD]))/.exec(t.replace(/^\.field\s+(?:(?:public|private|protected|static|final|volatile|transient|synthetic|enum)\s+)*/, ""));
      if (m) {
        const name = m[1];
        declared = { id: `${cls}.${name}:${m[2]}`, kind: "field", name, at: text.indexOf(name, indent + 7) };
      }
    } else if (t.startsWith(".class ")) {
      const m = /L?([\w$/]+);?\s*$/.exec(t);
      if (m) declared = { id: m[1], kind: "class", name: simple(m[1]), at: indent + m.index };
    }
    if (declared && declared.at >= 0) {
      const n = node(declared.id, declared.kind, declared.name);
      if (!doc.decls.has(declared.id)) doc.decls.set(declared.id, { line, col: declared.at });
      doc.declLines.push({ line, node: n });
      doc.links[line].push({ col: declared.at, len: declared.name.length, node: n, decl: true });
    }
    if (t.startsWith("#")) return;
    const jm = JVM_MEMBER.exec(text);
    if (jm && known(jm[1].split("$")[0])) {
      const [all, owner, member, desc] = jm;
      const ownerAt = jm.index + all.indexOf(owner);
      const memberAt = text.indexOf(member, ownerAt + owner.length);
      const kind = desc.startsWith("(") ? "method" : "field";
      const id = kind === "method" ? `${owner}.${member}${desc}` : `${owner}.${member}:${desc}`;
      doc.links[line].push({ col: ownerAt, len: owner.length, node: node(owner, "class", simple(owner)), decl: false });
      doc.links[line].push({ col: memberAt, len: member.length, node: node(id, kind, member), decl: false });
    }
    const jc = JVM_CLASS.exec(text);
    if (jc && known(jc[1].split("$")[0])) {
      doc.links[line].push({ col: jc.index + jc[0].lastIndexOf(jc[1]), len: jc[1].length, node: node(jc[1], "class", simple(jc[1])), decl: false });
    }
    SMALI_REF.lastIndex = 0;
    let m: RegExpExecArray | null;
    while ((m = SMALI_REF.exec(text))) {
      const [all, owner, member, desc] = m;
      if (!known(owner.split("$")[0]) && !known(owner)) continue;
      if (declared && m.index <= declared.at && declared.at < m.index + all.length) continue;
      if (member && desc) {
        const kind = desc.startsWith("(") ? "method" : "field";
        const at = m.index + all.indexOf("->") + 2;
        doc.links[line].push({ col: at, len: member.length, node: node(`${owner}.${member}${desc}`, kind, member), decl: false });
      }
      const start = m.index + all.indexOf("L");
      doc.links[line].push({ col: start, len: owner.length + 2, node: node(owner, "class", simple(owner)), decl: false });
    }
    doc.links[line].sort((a, b) => a.col - b.col);
  });
  return doc;
}

const simple = (id: string) => id.slice(id.lastIndexOf("/") + 1);

export function xmlDoc(source: string): Doc {
  return { ...base(source, highlightXml(source)), warnings: 0, ms: 0, engine: "xml" };
}

export interface Seg {
  text: string;
  kind: TokenKind;
  col: number;
  link?: Link;
}

/** Splits a line's colour tokens at its link boundaries, so each link is its own segment. */
export function segments(tokens: Token[], links: Link[]): Seg[] {
  const out: Seg[] = [];
  let col = 0;
  let li = 0;
  for (const [kind, text] of tokens) {
    let start = 0;
    while (start < text.length) {
      const abs = col + start;
      while (li < links.length && links[li].col + links[li].len <= abs) li++;
      const link = links[li];
      if (link && link.col <= abs) {
        const end = Math.min(text.length, link.col + link.len - col);
        out.push({ text: text.slice(start, end), kind, col: abs, link });
        start = end;
      } else {
        const end = link ? Math.min(text.length, link.col - col) : text.length;
        out.push({ text: text.slice(start, end), kind, col: abs });
        start = end;
      }
    }
    col += text.length;
  }
  return out;
}

const WORD = /[\w$]/;

/** The identifier around a column, if there is one. */
export function wordAt(line: string, col: number): { start: number; end: number } | null {
  let start = col;
  let end = col;
  while (start > 0 && WORD.test(line[start - 1])) start--;
  while (end < line.length && WORD.test(line[end])) end++;
  return end > start ? { start, end } : null;
}

export function linkAt(doc: Doc, pos: Pos): Link | undefined {
  return doc.links[pos.line]?.find((l) => l.col <= pos.col && pos.col <= l.col + l.len);
}

/** The innermost declaration at or above a line: roughly, what the line is inside of. */
export function enclosing(doc: Doc, line: number): NodeInfo | undefined {
  let found: number | undefined;
  for (const d of doc.declLines) {
    if (d.line > line) break;
    found = d.node;
  }
  return found === undefined ? undefined : doc.nodes[found];
}
