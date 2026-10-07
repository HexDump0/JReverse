// Sorting string constants by what they look like, for the Strings panel. Shapes only, no verdicts.

export type StringKind = "url" | "host" | "path" | "sql" | "encoded";

export const KIND_LABEL: Record<StringKind, string> = {
  url: "URLs",
  host: "Hosts and IPs",
  path: "Paths",
  sql: "SQL",
  encoded: "Base64 or hex",
};

// Enough top-level domains to tell `api.example.com` from a dotted Java name like `java.lang.String`.
const TLDS = new Set(
  "com net org io dev app co me ai gg tv info biz xyz cloud site online tech ru cn de uk fr jp kr br in it es nl pl eu us ca au ch se no fi dk be at cz ir tr ua vn id".split(" "),
);

const URL = /^[a-z][a-z0-9+.-]*:\/\/\S+$/i;
const IP = /^\d{1,3}(\.\d{1,3}){3}(:\d{1,5})?(\/\S*)?$/;
const HOST = /^(?:[a-z0-9-]+\.)+([a-z]{2,})(?::\d{1,5})?$/i;
const PATH = /^(?:\/[\w.@+-]+){2,}\/?$|^[A-Za-z]:\\|^~\/|^(?:\.{1,2}\/)[\w./-]+$/;
const FILE = /^[\w./-]+\.(?:json|xml|txt|properties|ya?ml|toml|db|sqlite|so|dll|jar|dex|png|jpe?g|ini|cfg|conf|log|dat|bin)$/i;
const SQL = /^\s*(?:SELECT|INSERT|UPDATE|DELETE|CREATE|DROP|ALTER|PRAGMA|REPLACE)\s/i;
const HEX = /^(?:0x)?[0-9a-f]{32,}$/i;
const B64 = /^[A-Za-z0-9+/_-]{20,}={0,2}$/;

export function kindOf(s: string): StringKind | null {
  if (URL.test(s)) return "url";
  if (IP.test(s)) return "host";
  const host = HOST.exec(s);
  if (host && TLDS.has(host[1].toLowerCase()) && !TLDS.has(s.slice(0, s.indexOf(".")).toLowerCase())) return "host";
  if (SQL.test(s)) return "sql";
  if (PATH.test(s) || FILE.test(s)) return "path";
  // Base64 needs a mix of cases and digits, so long identifiers don't count.
  if (HEX.test(s) || (B64.test(s) && /[a-z]/.test(s) && /[A-Z]/.test(s) && /\d/.test(s) && !/^[a-z]+(?:[A-Z][a-z]*)+\d*$/.test(s))) return "encoded";
  return null;
}

/** A string as Java source writes it, without the quotes, so it can be found in decompiled code. */
export function javaEscape(s: string): string {
  let out = "";
  for (const c of s) {
    const code = c.codePointAt(0)!;
    if (c === "\\") out += "\\\\";
    else if (c === '"') out += '\\"';
    else if (c === "\n") out += "\\n";
    else if (c === "\r") out += "\\r";
    else if (c === "\t") out += "\\t";
    else if (code < 0x20) out += `\\u${code.toString(16).padStart(4, "0")}`;
    else out += c;
  }
  return out;
}
