// Frida and Xposed hooks for a method, field or class, in the shapes jadx-gui's snippets use.
import type { NodeInfo } from "$lib/engine";
import { originalMember, ownerOf } from "./ids";

/** `com/foo/Bar$Inner` as Java.use wants it: `com.foo.Bar$Inner`. */
const javaName = (classId: string) => classId.replaceAll("/", ".");

function varName(classId: string): string {
  const simple = classId.slice(classId.lastIndexOf("/") + 1);
  const last = simple.slice(simple.lastIndexOf("$") + 1).replace(/[^\w$]/g, "_");
  return /^\d/.test(last) || !last ? `C${last}` : last;
}


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
    // Frida needs the original name. A method of the same name would make it `_name`.
    const field = originalMember(node.id);
    return `${use}\nconsole.log(\`${v}.${field} = \${${v}.${field}.value}\`);`;
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

const JAVA_LANG = /^java\.lang\.[A-Z]\w*$/;

/** An Xposed parameter type: `int.class`, `String.class`, `byte[].class`, or a class name. */
function xposedType(frida: string): string {
  if (!frida.includes(".") && !frida.startsWith("[")) return `${frida}.class`;
  if (JAVA_LANG.test(frida)) return `${frida.slice(10)}.class`;
  const prim: Record<string, string> = { Z: "boolean", B: "byte", S: "short", C: "char", I: "int", J: "long", F: "float", D: "double" };
  const dims = /^\[+/.exec(frida)?.[0].length ?? 0;
  if (dims) {
    const elem = frida.slice(dims);
    const brackets = "[]".repeat(dims);
    if (prim[elem]) return `${prim[elem]}${brackets}.class`;
    const name = elem.slice(1, -1);
    return JAVA_LANG.test(name) ? `${name.slice(10)}${brackets}.class` : `"${name}${brackets}"`;
  }
  return `"${frida}"`;
}

export function xposedSnippet(node: NodeInfo): string {
  const owner = node.kind === "class" ? node.id : ownerOf(node.id);
  const cls = javaName(owner);
  if (node.kind === "class") return `Class<?> ${varName(owner)} = XposedHelpers.findClass("${cls}", classLoader);`;
  if (node.kind === "field") {
    // Xposed looks fields up by their original name.
    const field = originalMember(node.id);
    const get = node.static ? `XposedHelpers.getStaticObjectField(XposedHelpers.findClass("${cls}", classLoader), "${field}")` : `XposedHelpers.getObjectField(obj, "${field}")`;
    return `Object ${field} = ${get};`;
  }
  const raw = node.id.slice(node.id.indexOf(".") + 1, node.id.indexOf("("));
  if (raw === "<clinit>") return "// Static initialisers run before a hook can be installed.";
  const args = (node.frida ?? []).map(xposedType);
  const call = raw === "<init>" ? `XposedHelpers.findAndHookConstructor("${cls}", classLoader` : `XposedHelpers.findAndHookMethod("${cls}", classLoader, "${raw}"`;
  return [
    `${call}${args.map((a) => `, ${a}`).join("")}, new XC_MethodHook() {`,
    "    @Override",
    "    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {",
    "        super.beforeHookedMethod(param);",
    "    }",
    "",
    "    @Override",
    "    protected void afterHookedMethod(MethodHookParam param) throws Throwable {",
    "        super.afterHookedMethod(param);",
    "    }",
    "});",
  ].join("\n");
}
