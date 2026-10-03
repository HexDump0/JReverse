// Frida hooks for a method, field or class, in the shape jadx-gui's "Copy as frida snippet" uses.
import type { NodeInfo } from "$lib/engine";

/** `com/foo/Bar$Inner` as Java.use wants it: `com.foo.Bar$Inner`. */
const javaName = (classId: string) => classId.replaceAll("/", ".");

function varName(classId: string): string {
  const simple = classId.slice(classId.lastIndexOf("/") + 1);
  const last = simple.slice(simple.lastIndexOf("$") + 1).replace(/[^\w$]/g, "_");
  return /^\d/.test(last) || !last ? `C${last}` : last;
}

/** The declaring class of a member id: everything before the first dot. */
export const ownerOf = (id: string) => (id.includes(".") ? id.slice(0, id.indexOf(".")) : id);

/**
 * Parameter names from a method's declaration line, e.g. `verify(String owner, String key)`.
 * Falls back to arg0.. when the line doesn't spell them out.
 */
export function paramNames(declLine: string | undefined, count: number): string[] {
  const fallback = Array.from({ length: count }, (_, i) => `arg${i}`);
  if (!declLine || count === 0) return fallback;
  const open = declLine.indexOf("(");
  if (open < 0) return fallback;
  let depth = 0;
  let end = open;
  for (let i = open; i < declLine.length; i++) {
    const c = declLine[i];
    if (c === "(" || c === "<") depth++;
    else if (c === ")" || c === ">") depth--;
    if (depth === 0) {
      end = i;
      break;
    }
  }
  const parts: string[] = [];
  let cur = "";
  depth = 0;
  for (const c of declLine.slice(open + 1, end)) {
    if (c === "<") depth++;
    else if (c === ">") depth--;
    if (c === "," && depth === 0) {
      parts.push(cur);
      cur = "";
    } else cur += c;
  }
  if (cur.trim()) parts.push(cur);
  const names = parts.map((p) => /([\w$]+)\s*$/.exec(p.trim())?.[1] ?? "");
  return names.length === count && names.every((n) => n) ? names : fallback;
}

export function fridaSnippet(node: NodeInfo, declLine?: string): string {
  const owner = node.kind === "class" ? node.id : ownerOf(node.id);
  const v = varName(owner);
  const use = `let ${v} = Java.use("${javaName(owner)}");`;
  if (node.kind === "class") return use;
  if (node.kind === "field") {
    return `${use}\nconsole.log(\`${v}.${node.name} = \${${v}._${node.name}.value}\`);`;
  }
  const raw = node.id.slice(node.id.indexOf(".") + 1, node.id.indexOf("("));
  const isInit = raw === "<init>";
  const method = isInit ? "$init" : raw === "<clinit>" ? null : raw;
  if (!method) return `${use}\n// Static initialisers run before Frida can hook them.`;
  const args = node.frida ?? [];
  const names = paramNames(declLine, args.length);
  const list = names.join(", ");
  const overload = `.overload(${args.map((a) => `"${a}"`).join(", ")})`;
  const shown = names.map((n) => `${n}=\${${n}}`).join(", ");
  const label = `${v}.${isInit ? "$init" : node.name}`;
  const returns = !isInit && !/:\s*void$/.test(node.detail);
  const body = [
    `    console.log(\`${label} is called${shown ? `: ${shown}` : ""}\`);`,
    returns ? `    let result = this["${method}"](${list});` : `    this["${method}"](${list});`,
    ...(returns ? [`    console.log(\`${label} result=\${result}\`);`, "    return result;"] : []),
  ];
  return `${use}\n${v}["${method}"]${overload}.implementation = function (${list}) {\n${body.join("\n")}\n};`;
}
