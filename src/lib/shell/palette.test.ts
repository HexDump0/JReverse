import { describe, expect, it } from "vitest";
import { score } from "./Palette.svelte";

describe("palette score", () => {
  it("ranks exact, prefix, initials, then substring", () => {
    expect(score("Vault", "vault")).toBe(0);
    expect(score("VaultStore", "vault")).toBe(1);
    expect(score("LicenseCheck", "lc")).toBe(2);
    expect(score("MyLicense", "license")).toBe(3);
    expect(score("Vault", "zz")).toBe(-1);
  });
});
