import { describe, expect, it } from "vitest";
import { rank, score, type PaletteItem } from "./Palette.svelte";

describe("palette score", () => {
  it("ranks exact, prefix, initials, then substring", () => {
    expect(score("Vault", "vault")).toBe(0);
    expect(score("VaultStore", "vault")).toBe(1);
    expect(score("LicenseCheck", "lc")).toBe(2);
    expect(score("MyLicense", "license")).toBe(3);
    expect(score("Vault", "zz")).toBe(-1);
  });

  it("puts the file's own classes before library ones that match as well", () => {
    const item = (label: string, library = false): PaletteItem => ({ section: "Classes", label, library, run: () => {} });
    const items = [item("RecyclerView", true), item("Recorder"), item("Rec", true)];
    const ranked = rank(items.map((it, i) => [score(it.label, "rec"), i, it]));
    expect(ranked.map((it) => it.label)).toEqual(["Rec", "Recorder", "RecyclerView"]);
  });
});
