import { describe, expect, it } from "vitest";
import { androidVersion, groupClasses, libraryOf, looksObfuscated, permissionLevel } from "./android";

describe("libraries", () => {
  it("prefers the longest prefix", () => {
    expect(libraryOf("com/facebook/react/Bridge")).toBe("React Native");
    expect(libraryOf("com/facebook/login/Login")).toBe("Facebook SDK");
    expect(libraryOf("org/fdroid/Main")).toBeNull();
  });

  it("puts the app's own code first", () => {
    const classes = ["androidx/a/A", "androidx/a/B", "androidx/a/C", "org/fdroid/fdroid/Main", "org/fdroid/fdroid/ui/V"].map((id) => ({
      id,
      kind: "class" as const,
    }));
    const { groups } = groupClasses(classes, "org.fdroid.fdroid");
    expect(groups.map((g) => [g.name, g.classes, g.library])).toEqual([
      ["org.fdroid.fdroid", 2, false],
      ["AndroidX", 3, true],
    ]);
  });
});

describe("obfuscation", () => {
  it("spots ProGuard-style names", () => {
    expect(looksObfuscated("a/b/c")).toBe(true);
    expect(looksObfuscated("com/x/a")).toBe(true);
    expect(looksObfuscated("com/example/LoginActivity")).toBe(false);
  });
});

describe("permissions", () => {
  it("sorts by how much they grant", () => {
    expect(permissionLevel("android.permission.CAMERA")).toBe("dangerous");
    expect(permissionLevel("android.permission.SYSTEM_ALERT_WINDOW")).toBe("special");
    expect(permissionLevel("android.permission.INTERNET")).toBe("normal");
    expect(permissionLevel("com.example.PRIVATE")).toBe("custom");
    expect(androidVersion("34")).toBe("Android 14");
    expect(androidVersion("3")).toBeNull();
  });
});
