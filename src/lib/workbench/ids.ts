// Node ids, as the engine spells them: `com/foo/Bar`, `com/foo/Bar.run(I)V`, `com/foo/Bar.count:I`.

/** The declaring class of a member id: everything before the first dot. */
export const ownerOf = (id: string) => (id.includes(".") ? id.slice(0, id.indexOf(".")) : id);

/** A member's name before any rename: `count` from `com/foo/Bar.count:I`. */
export const originalMember = (id: string) => id.slice(id.indexOf(".") + 1).split(/[(:]/)[0];

/** A class's own name, without package or outer classes: `Inner` from `com/foo/Bar$Inner`. */
export const simpleName = (id: string) => {
  const s = id.slice(id.lastIndexOf("/") + 1);
  return s.slice(s.lastIndexOf("$") + 1);
};

export const dotted = (id: string) => id.replaceAll("/", ".");
