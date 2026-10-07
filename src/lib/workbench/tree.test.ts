import { describe, expect, it } from "vitest";
import { buildClassTree, buildTree, LIBRARIES, OBFUSCATED, pathsTo, visibleRows } from "./tree";

const cls = (id: string) => ({ id, kind: "class" as const });

describe("tree", () => {
  const root = buildTree([cls("com/example/app/Main"), cls("com/example/app/ui/View"), cls("com/example/app/ui/List"), cls("Top")]);

  it("merges packages that only hold one package", () => {
    expect(root.pkgs.map((p) => p.label)).toEqual(["com.example.app"]);
    expect(root.pkgs[0].total).toBe(3);
    expect(root.classes.map((c) => c.id)).toEqual(["Top"]);
  });

  it("shows only open packages' contents", () => {
    expect(visibleRows(root, new Set()).map((r) => r.key)).toEqual(["com/example/app", "Top"]);
    const open = visibleRows(root, new Set(["com/example/app", "com/example/app/ui"]));
    expect(open.map((r) => r.key)).toEqual([
      "com/example/app",
      "com/example/app/ui",
      "com/example/app/ui/List",
      "com/example/app/ui/View",
      "com/example/app/Main",
      "Top",
    ]);
    expect(open.find((r) => r.key === "com/example/app/ui/View")?.depth).toBe(2);
  });

  it("knows which packages to open to reveal a class", () => {
    expect(pathsTo(root, "com/example/app/ui/View")).toEqual(["com/example/app", "com/example/app/ui"]);
    expect(pathsTo(root, "Top")).toEqual([]);
  });

  it("groups libraries after the app's own packages", () => {
    const lib = (id: string) => id.startsWith("okhttp3/") || id.startsWith("kotlin/");
    const t = buildClassTree([cls("com/app/Main"), cls("okhttp3/Call"), cls("kotlin/Unit"), cls("a/a"), cls("a/b"), cls("a/c")], lib, (id) => id.startsWith("a/"));
    expect(t.pkgs.map((p) => p.label)).toEqual(["a", "com.app", "Libraries"]);
    expect(t.pkgs[0].obf).toBe(true);
    expect(t.pkgs[2].pkgs.map((p) => p.label)).toEqual(["kotlin", "okhttp3"]);
    expect(pathsTo(t, "okhttp3/Call")).toEqual([LIBRARIES, "okhttp3"]);
    // All library: nothing to put first, so no group.
    expect(buildClassTree([cls("okhttp3/Call")], lib, () => false).pkgs.map((p) => p.label)).toEqual(["okhttp3"]);
  });

  it("groups obfuscated top-level packages, but not short real ones", () => {
    const obf = (id: string) => /(^|\/)[a-zA-Z]{1,2}\d?$/.test(id) || id.split("/").slice(0, -1).every((p) => p.length <= 2);
    const t = buildClassTree(
      [cls("a/a"), cls("b/c"), cls("b0/d"), cls("c"), cls("com/app/Main"), cls("io/github/x/Tool"), cls("io/github/x/Other")],
      () => false,
      obf,
    );
    expect(t.pkgs.map((p) => p.label)).toEqual(["com.app", "io.github.x", "Obfuscated"]);
    expect(t.pkgs[2].total).toBe(4);
    expect(pathsTo(t, "b0/d")).toEqual([OBFUSCATED, "b0"]);
  });
});
