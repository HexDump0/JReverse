// Frida and Xposed hooks for a method, field or class, in the shapes jadx-gui's snippets use.
import type { NodeInfo, Overview } from "$lib/engine";
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

const PRIM: Record<string, string> = { Z: "boolean", B: "byte", S: "short", C: "char", I: "int", J: "long", F: "float", D: "double", V: "void" };
const BOXED: Record<string, string> = { boolean: "Boolean", byte: "Byte", short: "Short", char: "Character", int: "Integer", long: "Long", float: "Float", double: "Double" };

/** A JVM descriptor type (`I`, `[Ljava/lang/String;`) as Java source spells it; `java.lang` is left out. */
export function descriptorType(desc: string): string {
  const dims = /^\[+/.exec(desc)?.[0].length ?? 0;
  const elem = desc.slice(dims);
  const name = PRIM[elem] ?? elem.slice(1, -1).replaceAll("/", ".").replaceAll("$", ".");
  return (JAVA_LANG.test(name) ? name.slice(10) : name) + "[]".repeat(dims);
}

/** Splits `(ILjava/lang/String;[B)Z` into its argument and return types. */
function parseMethodDescriptor(desc: string): { args: string[]; ret: string } {
  const args: string[] = [];
  let i = desc.indexOf("(") + 1;
  while (desc[i] !== ")") {
    let j = i;
    while (desc[j] === "[") j++;
    j = desc[j] === "L" ? desc.indexOf(";", j) + 1 : j + 1;
    args.push(desc.slice(i, j));
    i = j;
  }
  return { args, ret: desc.slice(i + 1) };
}

/** `com.foo.Bar#run(int, String)`, the way Javadoc links and IDE references spell a member. */
export function javaReference(node: NodeInfo): string {
  const owner = javaName(node.kind === "class" ? node.id : ownerOf(node.id)).replaceAll("$", ".");
  if (node.kind === "class") return owner;
  if (node.kind === "field") return `${owner}#${originalMember(node.id)}`;
  const member = node.id.slice(node.id.indexOf(".") + 1);
  const { args } = parseMethodDescriptor(member);
  const name = member.startsWith("<init>") ? owner.slice(owner.lastIndexOf(".") + 1) : originalMember(node.id);
  return `${owner}#${name}(${args.map(descriptorType).join(", ")})`;
}

/** `com/foo/Bar.run(I)V`: ASM, javap -s, Recaf and Mixin all name members this way. */
export const jvmDescriptor = (node: NodeInfo) => node.id;

/** `Lcom/foo/Bar;->run(I)V`, the way smali and most Android hooking tools spell it. */
export function smaliReference(node: NodeInfo): string {
  if (node.kind === "class") return `L${node.id};`;
  const owner = ownerOf(node.id);
  return `L${owner};->${node.id.slice(owner.length + 1)}`;
}

/** A Mixin class that injects into a method, reads a field, or targets a class. */
export function mixinSnippet(node: NodeInfo, declLine?: string): string {
  const owner = node.kind === "class" ? node.id : ownerOf(node.id);
  const simple = varName(owner).replace(/Mixin$/, "");
  // `targets` takes the binary name, so it works for private and inner classes and needs no import.
  const head = `@Mixin(targets = "${javaName(owner)}")`;
  if (node.kind === "class") return `${head}\npublic abstract class ${simple}Mixin {\n}`;
  if (node.kind === "field") {
    const name = originalMember(node.id);
    const type = descriptorType(node.id.slice(node.id.indexOf(":") + 1));
    const getter = `${type === "boolean" ? "is" : "get"}${name[0].toUpperCase()}${name.slice(1)}`;
    const body = node.static ? `static ${type} ${getter}() {\n        throw new AssertionError();\n    }` : `${type} ${getter}();`;
    return `${head}\npublic interface ${simple}Accessor {\n    @Accessor("${name}")\n    ${body}\n}`;
  }
  const member = node.id.slice(node.id.indexOf(".") + 1);
  const { args, ret } = parseMethodDescriptor(member);
  const isInit = member.startsWith("<init>");
  const raw = originalMember(node.id);
  const names = paramNames(declLine, args.length);
  const returnType = descriptorType(ret);
  const callback = ret === "V" || isInit ? "CallbackInfo ci" : `CallbackInfoReturnable<${BOXED[returnType] ?? returnType}> cir`;
  const params = [...args.map((a, i) => `${descriptorType(a)} ${names[i]}`), callback].join(", ");
  const capital = raw[0].toUpperCase() + raw.slice(1);
  const handler = isInit ? "onInit" : raw === "<clinit>" ? "onStaticInit" : /^on[A-Z]/.test(raw) ? raw : `on${capital}`;
  // A constructor can only be injected into once it has called super(), so at its end.
  const at = isInit ? "TAIL" : "HEAD";
  return [
    head,
    `public abstract class ${simple}Mixin {`,
    `    @Inject(method = "${member}", at = @At("${at}"))`,
    `    private ${node.static ? "static " : ""}void ${handler}(${params}) {`,
    "    }",
    "}",
  ].join("\n");
}

/** A Minecraft mod, or anything with mixins: where a Mixin injector is worth offering. */
export const isMod = (o: Overview | null | undefined) =>
  !!o && (!!o.mixinConfigs?.length || !!o.plugins?.some((p) => ["fabric", "quilt", "forge", "neoforge"].includes(p.loader)));

export type CopyFormat = "frida" | "xposed" | "smali" | "reference" | "descriptor" | "mixin";

export const FORMAT_LABEL: Record<CopyFormat, string> = {
  frida: "Frida",
  xposed: "Xposed",
  smali: "Smali",
  reference: "Reference",
  descriptor: "Descriptor",
  mixin: "Mixin",
};

/** What to offer for a file: Android hooks for Android code, JVM names (and Mixin for mods) otherwise. */
export function copyFormats(kind: string, mod: boolean): CopyFormat[] {
  if (kind === "apk" || kind === "aab" || kind === "dex") return ["frida", "xposed", "smali"];
  if (kind === "aar") return ["frida", "xposed", "descriptor"];
  return mod ? ["mixin", "reference", "descriptor"] : ["reference", "descriptor"];
}

export function copyAs(format: CopyFormat, node: NodeInfo, declLine?: string): string {
  switch (format) {
    case "frida":
      return fridaSnippet(node, declLine);
    case "xposed":
      return xposedSnippet(node);
    case "smali":
      return smaliReference(node);
    case "reference":
      return javaReference(node);
    case "descriptor":
      return jvmDescriptor(node);
    case "mixin":
      return mixinSnippet(node, declLine);
  }
}
