// Colours smali and the JVM bytecode listing jadx prints for class files.
// Same token kinds as highlight.ts so the code view styles both alike.
import type { Token, TokenKind } from "./highlight";

const TOKEN =
  /(#.*)|("(?:[^"\\]|\\.)*")|(\.[a-z][\w-]*)|(\b[vp]\d+\b)|(\[*L[\w$/]+;|\[+[ZBSCIJFDV]\b)|(-?\b(?:0x[\da-fA-F]+|\d+)[LtsfdF]?\b)|(->[\w$<>]+)|(:[\w]+)|([A-Za-z_$][\w$/-]*)/g;

const DIRECTIVE_ARGS = new Set(["public", "private", "protected", "static", "final", "abstract", "synthetic", "constructor", "bridge", "varargs", "native", "interface", "enum", "annotation", "transient", "volatile", "strictfp", "synchronized", "declared-synchronized", "super"]);

export function highlightSmali(source: string): Token[][] {
  return source.split("\n").map((text) => {
    const line: Token[] = [];
    let pos = 0;
    TOKEN.lastIndex = 0;
    let m: RegExpExecArray | null;
    let first = true;
    while ((m = TOKEN.exec(text))) {
      if (m.index > pos) line.push(["", text.slice(pos, m.index)]);
      const [all, comment, str, directive, reg, type, num, member, label, word] = m;
      let kind: TokenKind = "";
      if (comment) kind = "c";
      else if (str) kind = "s";
      else if (directive) kind = "k";
      else if (reg) kind = "a";
      else if (type) kind = "t";
      else if (num) kind = "n";
      else if (member) kind = "m";
      else if (label) kind = "a";
      else if (word) kind = DIRECTIVE_ARGS.has(word) ? "k" : first ? "m" : "";
      line.push([kind, all]);
      first = false;
      pos = m.index + all.length;
    }
    if (pos < text.length) line.push(["", text.slice(pos)]);
    return line;
  });
}
