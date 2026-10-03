import { describe, expect, it } from "vitest";
import type { Decompiled } from "$lib/engine";
import { enclosing, findDecl, javaDoc, linkAt, segments, smaliDoc, wordAt, xmlDoc } from "./doc";

const node = (id: string, kind: "class" | "method" | "field", name: string) => ({
  kind,
  id,
  top: "a/B",
  name,
  detail: name,
  access: "public" as const,
  static: false,
});

describe("javaDoc", () => {
  const source = "class B {\n    int run() {\n        return run();\n    }\n}";
  const d: Decompiled = {
    source,
    engine: "jadx",
    ms: 3,
    warnings: 0,
    nodes: [node("a/B", "class", "B"), node("a/B.run()I", "method", "run")],
    // B declared at 0:6, run declared at 1:8, run called at 2:15.
    links: [0, 6, 1, 0, 1, 8, 3, 1, 2, 15, 3, 1],
    decls: [0, 6, 1, 0, 1, 8, 3, 1],
  };
  const doc = javaDoc(d);

  it("indexes declarations", () => {
    expect(doc.decls.get("a/B.run()I")).toEqual({ line: 1, col: 8 });
    expect(doc.declLines.map((x) => x.line)).toEqual([0, 1]);
  });

  it("finds the link under the caret, including at its end", () => {
    expect(linkAt(doc, { line: 2, col: 15 })?.decl).toBe(false);
    expect(linkAt(doc, { line: 2, col: 18 })?.node).toBe(1);
    expect(linkAt(doc, { line: 2, col: 19 })).toBeUndefined();
  });

  it("knows what a line is inside of", () => {
    expect(enclosing(doc, 2)?.id).toBe("a/B.run()I");
    expect(enclosing(doc, 0)?.id).toBe("a/B");
  });
});

describe("segments", () => {
  it("splits tokens at link edges", () => {
    const segs = segments([["", "x.foo(y)"]], [{ col: 2, len: 3, node: 0, decl: false }]);
    expect(segs.map((s) => [s.text, s.col, !!s.link])).toEqual([
      ["x.", 0, false],
      ["foo", 2, true],
      ["(y)", 5, false],
    ]);
  });

  it("handles a link spanning several tokens", () => {
    const segs = segments(
      [["t", "com"], ["", "."], ["t", "Foo"]],
      [{ col: 0, len: 7, node: 0, decl: false }],
    );
    expect(segs.every((s) => s.link)).toBe(true);
    expect(segs.map((s) => s.text).join("")).toBe("com.Foo");
  });
});

describe("wordAt", () => {
  it("finds identifiers around a column", () => {
    expect(wordAt("  foo.bar_1(", 6)).toEqual({ start: 6, end: 11 });
    expect(wordAt("  foo.bar(", 5)).toEqual({ start: 2, end: 5 });
    expect(wordAt("a + b", 2)).toBeNull();
  });
});

describe("smaliDoc", () => {
  const known = (id: string) => id === "a/B" || id === "a/C";

  it("links smali references and indexes declarations", () => {
    const src = [
      ".class public La/B;",
      ".field private count:I",
      ".method public run(I)V",
      "    invoke-virtual {p0}, La/C;->go(Ljava/lang/String;)V",
      "    iget v0, p0, La/B;->count:I",
      "    invoke-static {}, Ljava/lang/System;->exit(I)V",
      ".end method",
    ].join("\n");
    const doc = smaliDoc("a/B", src, 1, known);
    expect(doc.decls.get("a/B")).toEqual({ line: 0, col: 17 });
    expect(doc.decls.get("a/B.count:I")?.line).toBe(1);
    expect(doc.decls.get("a/B.run(I)V")?.line).toBe(2);
    const ids = (line: number) => doc.links[line].map((l) => doc.nodes[l.node].id);
    expect(ids(3)).toEqual(["a/C", "a/C.go(Ljava/lang/String;)V"]);
    expect(ids(4)).toContain("a/B.count:I");
    // Classes outside the input aren't links.
    expect(ids(5)).toEqual([]);
  });

  it("links jadx's JVM bytecode listing too", () => {
    const src = ["    invokevirtual a/C go (I)V", "    getfield a/B count I", "    new a/C"].join("\n");
    const doc = smaliDoc("a/B", src, 1, known);
    const ids = (line: number) => doc.links[line].map((l) => doc.nodes[l.node].id);
    expect(ids(0)).toEqual(["a/C", "a/C.go(I)V"]);
    expect(ids(1)).toEqual(["a/B", "a/B.count:I"]);
    expect(ids(2)).toEqual(["a/C"]);
    const go = doc.links[0][1];
    expect(src.split("\n")[0].slice(go.col, go.col + go.len)).toBe("go");
  });
});

describe("xmlDoc", () => {
  it("links component classes, resolving short names", () => {
    const xml = '<activity android:name=".Main"/>\n<service android:name="com.x.Sync"/>\n<receiver android:name="other.Gone"/>';
    const doc = xmlDoc(xml, "com.x", (id) => id === "com/x/Main" || id === "com/x/Sync");
    expect(doc.links[0].map((l) => doc.nodes[l.node].id)).toEqual(["com/x/Main"]);
    expect(xml.split("\n")[0].slice(doc.links[0][0].col, doc.links[0][0].col + doc.links[0][0].len)).toBe(".Main");
    expect(doc.links[1].length).toBe(1);
    expect(doc.links[2]).toEqual([]);
  });
});

describe("findDecl", () => {
  // Vineflower-style output: no index, so declarations are read from the text.
  const source = [
    "public final class Vault {",
    "    private final Map<String, Vault.Entry> entries = new LinkedHashMap<>();",
    "    public Vault(License var1) {",
    "        return put(var1);",
    "    }",
    "    public boolean put(String var1, String var2) {",
    "    private static final class Entry {",
  ].join("\n");
  const doc = javaDoc({ source, engine: "vineflower", ms: 1, warnings: 0, nodes: [], links: [], decls: [] });
  const find = (kind: "class" | "method" | "field", id: string) => findDecl(doc, { ...node(id, kind, "renamed"), kind });

  it("finds methods, constructors, fields and classes by their original names", () => {
    expect(find("method", "a/Vault.put(Ljava/lang/String;Ljava/lang/String;)Z")).toEqual({ line: 5, col: 19 });
    expect(find("method", "a/Vault.<init>(La/License;)V")).toEqual({ line: 2, col: 11 });
    expect(find("field", "a/Vault.entries:Ljava/util/Map;")).toEqual({ line: 1, col: 43 });
    expect(find("class", "a/Vault$Entry")).toEqual({ line: 6, col: 31 });
    expect(find("method", "a/Vault.gone()V")).toBeUndefined();
  });
});
