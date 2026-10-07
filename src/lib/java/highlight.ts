// A small Java highlighter for decompiler output: enough to colour keywords,
// types, methods, names, constants, strings, numbers and comments, and to dim
// punctuation, line by line. Not a parser.

/** `v` a plain name, `f` a CONSTANT, `p` punctuation; "" is whitespace or anything else. */
export type TokenKind = "" | "k" | "t" | "m" | "f" | "v" | "p" | "s" | "n" | "c" | "a";
export type Token = [TokenKind, string];

const KEYWORDS = new Set(
  (
    "abstract assert boolean break byte case catch char class const continue default do double else enum " +
    "extends final finally float for goto if implements import instanceof int interface long native new " +
    "package private protected public record return short static strictfp super switch synchronized this " +
    "throw throws transient try var void volatile while yield true false null sealed permits non-sealed"
  ).split(" "),
);

const TOKEN =
  /(\/\/.*)|(\/\*)|("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')|(@[A-Za-z_]\w*)|(\b(?:0[xX][\da-fA-F_]+|\d[\d_]*(?:\.\d+)?)[lLfFdD]?\b)|([A-Za-z_$][\w$]*)(?=\s*\()|([A-Za-z_$][\w$]*)/g;

const CONSTANT = /^[A-Z][A-Z\d_]*[A-Z\d]$/;

/** Text between tokens: punctuation, unless it's only spaces. */
const gap = (t: string): Token => [/\S/.test(t) ? "p" : "", t];

/** Splits `source` into lines of tokens. Block comments may span lines. */
export function highlight(source: string): Token[][] {
  const lines: Token[][] = [];
  let inComment = false;
  for (const text of source.split("\n")) {
    const line: Token[] = [];
    let pos = 0;
    if (inComment) {
      const end = text.indexOf("*/");
      pos = end < 0 ? text.length : end + 2;
      line.push(["c", text.slice(0, pos)]);
      inComment = end < 0;
    }
    TOKEN.lastIndex = pos;
    let m: RegExpExecArray | null;
    while (!inComment && (m = TOKEN.exec(text))) {
      if (m.index > pos) line.push(gap(text.slice(pos, m.index)));
      const [all, lineComment, blockStart, str, annotation, num, method, word] = m;
      if (blockStart) {
        const end = text.indexOf("*/", m.index + 2);
        const stop = end < 0 ? text.length : end + 2;
        line.push(["c", text.slice(m.index, stop)]);
        pos = TOKEN.lastIndex = stop;
        inComment = end < 0;
        continue;
      }
      let kind: TokenKind = "";
      if (lineComment) kind = "c";
      else if (str) kind = "s";
      else if (annotation) kind = "a";
      else if (num) kind = "n";
      else if (method) kind = KEYWORDS.has(method) ? "k" : "m";
      else if (word) kind = KEYWORDS.has(word) ? "k" : CONSTANT.test(word) ? "f" : /^[A-Z]/.test(word) ? "t" : "v";
      line.push([kind, all]);
      pos = m.index + all.length;
    }
    if (pos < text.length) line.push(gap(text.slice(pos)));
    lines.push(line);
  }
  return lines;
}
