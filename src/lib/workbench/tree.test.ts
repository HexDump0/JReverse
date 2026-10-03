import { describe, expect, it } from "vitest";
import { buildTree, pathsTo, visibleRows } from "./tree";

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
});
