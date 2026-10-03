import { describe, expect, it } from "vitest";
import type { NodeInfo } from "$lib/engine";
import { fridaSnippet, paramNames, xposedSnippet } from "./hooks";

const method = (id: string, detail: string, frida: string[]): NodeInfo => ({
  kind: "method",
  id,
  top: id.split(".")[0],
  name: id.slice(id.indexOf(".") + 1, id.indexOf("(")),
  detail,
  access: "public",
  static: true,
  frida,
});

describe("fridaSnippet", () => {
  it("hooks a method with its overload and parameter names", () => {
    const m = method("com/x/License.verify(Ljava/lang/String;[B)Z", "verify(String, byte[]): boolean", ["java.lang.String", "[B"]);
    expect(fridaSnippet(m, "    public static boolean verify(String key, byte[] data) {")).toBe(
      [
        'let License = Java.use("com.x.License");',
        'License["verify"].overload("java.lang.String", "[B").implementation = function (key, data) {',
        "    console.log(`License.verify is called: key=${key}, data=${data}`);",
        '    let result = this["verify"](key, data);',
        "    console.log(`License.verify result=${result}`);",
        "    return result;",
        "};",
      ].join("\n"),
    );
  });

  it("uses $init for constructors and skips the result for void", () => {
    const ctor = method("com/x/A$Inner.<init>(I)V", "Inner(int)", ["int"]);
    const s = fridaSnippet(ctor);
    expect(s).toContain('Java.use("com.x.A$Inner")');
    expect(s).toContain('Inner["$init"].overload("int").implementation = function (arg0) {');
    expect(s).not.toContain("result");
  });

  it("reads a field's value", () => {
    const f: NodeInfo = { kind: "field", id: "com/x/A.KEY:I", top: "com/x/A", name: "KEY", detail: "KEY: int", access: "", static: true };
    expect(fridaSnippet(f)).toBe('let A = Java.use("com.x.A");\nconsole.log(`A.KEY = ${A._KEY.value}`);');
  });
});

describe("paramNames", () => {
  it("handles generics and falls back when names don't line up", () => {
    expect(paramNames("void put(Map<String, List<Integer>> map, int n) {", 2)).toEqual(["map", "n"]);
    expect(paramNames("void put(String a) {", 2)).toEqual(["arg0", "arg1"]);
    expect(paramNames(undefined, 1)).toEqual(["arg0"]);
  });
});

describe("xposedSnippet", () => {
  it("passes primitives, java.lang and arrays as class literals, others by name", () => {
    const m = method("com/x/A.run(I[BLjava/lang/String;Lcom/x/B;[Lcom/x/B;)V", "run(...): void", ["int", "[B", "java.lang.String", "com.x.B", "[Lcom.x.B;"]);
    expect(xposedSnippet(m).split("\n")[0]).toBe(
      'XposedHelpers.findAndHookMethod("com.x.A", classLoader, "run", int.class, byte[].class, String.class, "com.x.B", "com.x.B[]", new XC_MethodHook() {',
    );
  });

  it("hooks constructors with findAndHookConstructor", () => {
    const ctor = method("com/x/A.<init>()V", "A()", []);
    expect(xposedSnippet(ctor).split("\n")[0]).toBe('XposedHelpers.findAndHookConstructor("com.x.A", classLoader, new XC_MethodHook() {');
  });
});
