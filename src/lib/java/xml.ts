// Colours XML (the decoded AndroidManifest.xml): tags, attribute names, values, comments.
import type { Token } from "./highlight";

const TOKEN = /(<!--.*?-->)|(<\/?[\w:.-]+|\/?>|<\?xml|\?>)|([\w:.-]+)(?==)|("[^"]*")/g;

export function highlightXml(source: string): Token[][] {
  return source.split("\n").map((text) => {
    const line: Token[] = [];
    let pos = 0;
    TOKEN.lastIndex = 0;
    let m: RegExpExecArray | null;
    while ((m = TOKEN.exec(text))) {
      if (m.index > pos) line.push(["", text.slice(pos, m.index)]);
      const [all, comment, tag, attr, value] = m;
      line.push([comment ? "c" : tag ? "k" : attr ? "t" : value ? "s" : "", all]);
      pos = m.index + all.length;
    }
    if (pos < text.length) line.push(["", text.slice(pos)]);
    return line;
  });
}
