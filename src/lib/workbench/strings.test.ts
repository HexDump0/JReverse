import { describe, expect, it } from "vitest";
import { javaEscape, kindOf } from "./strings";

describe("kindOf", () => {
  it("tells hosts from dotted Java names", () => {
    expect(kindOf("https://api.example.com/v1?x=1")).toBe("url");
    expect(kindOf("api.example.com")).toBe("host");
    expect(kindOf("10.0.2.2:8080")).toBe("host");
    expect(kindOf("com.google.android.gms")).toBeNull();
    expect(kindOf("java.lang.String")).toBeNull();
    expect(kindOf("android.intent.action.VIEW")).toBeNull();
  });

  it("finds paths, SQL and encoded blobs", () => {
    expect(kindOf("/data/local/tmp/frida")).toBe("path");
    expect(kindOf("config/settings.json")).toBe("path");
    expect(kindOf("SELECT * FROM users WHERE id = ?")).toBe("sql");
    expect(kindOf("d41d8cd98f00b204e9800998ecf8427e")).toBe("encoded");
    expect(kindOf("SGVsbG8gV29ybGQhIFRoaXMgaXM=")).toBe("encoded");
    expect(kindOf("getAdaptationManagerInstance")).toBeNull();
    expect(kindOf("Hello, ")).toBeNull();
  });
});

describe("javaEscape", () => {
  it("writes a string the way decompiled source does", () => {
    expect(javaEscape('say "hi"\n\tC:\\x')).toBe('say \\"hi\\"\\n\\tC:\\\\x');
  });
});
