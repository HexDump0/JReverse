package io.github.hexdump0.jreverse.engine.backend;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.api.JavaNode;

import net.fabricmc.mappingio.MappedElementKind;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.MappingWriter;
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.tree.MappingTreeView;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

import io.github.hexdump0.jreverse.engine.rpc.ErrorCode;
import io.github.hexdump0.jreverse.engine.rpc.RpcException;

/**
 * Renames and comments to and from mapping files (ProGuard, Tiny, Enigma, SRG
 * and the rest mapping-io knows), keyed by the engine's node ids.
 */
public final class Mappings {

	/** What {@link #write} offers, by the name the client uses. */
	public static final Map<String, MappingFormat> WRITABLE = Map.of(
			"proguard", MappingFormat.PROGUARD_FILE,
			"tiny2", MappingFormat.TINY_2_FILE,
			"enigma", MappingFormat.ENIGMA_FILE,
			"tsrg2", MappingFormat.TSRG_2_FILE);

	private static final String FILE_NS = "obfuscated";
	private static final String NAMED_NS = "named";

	/**
	 * @param renames  node id to new name; classes get a simple name
	 * @param matched  how many of the file's classes, methods and fields it named
	 * @param mappings how many classes, methods and fields the file had
	 */
	public record Read(String format, Map<String, String> renames, Map<String, String> comments, int matched, int mappings) {
	}

	private Mappings() {
	}

	/**
	 * Reads a mapping file and matches it to this file's classes. Which side of
	 * the mappings is "this file" is decided by which namespace's class names
	 * match the most classes, so ProGuard's {@code named -> obfuscated} and
	 * Tiny's {@code intermediary -> named} both work.
	 */
	public static Read read(JadxBackend jadx, Path path) throws RpcException {
		MemoryMappingTree tree = new MemoryMappingTree();
		MappingFormat format;
		try {
			format = MappingReader.detectFormat(path);
			if (format == null) {
				throw new RpcException(ErrorCode.BAD_REQUEST, "not a mapping file mapping-io knows: " + path.getFileName());
			}
			MappingReader.read(path, format, tree);
		} catch (IOException | RuntimeException e) {
			throw new RpcException(ErrorCode.DECODE_FAILED, "could not read " + path.getFileName() + ": " + e.getMessage(), e);
		}
		Index index = new Index(jadx);
		int from = bestNamespace(tree, index.classes);
		int to = otherNamespace(tree, from);

		Map<String, String> renames = new TreeMap<>();
		Map<String, String> comments = new TreeMap<>();
		int matched = 0;
		int mappings = 0;
		for (MappingTreeView.ClassMappingView c : tree.getClasses()) {
			mappings += 1 + c.getMethods().size() + c.getFields().size();
			String cls = c.getName(from);
			if (cls == null || !index.classes.contains(cls)) {
				continue;
			}
			matched++;
			String name = simple(c.getName(to));
			if (name != null && !name.equals(simple(cls))) {
				renames.put(cls, name);
			}
			comment(comments, cls, c.getComment());
			for (MappingTreeView.MethodMappingView m : c.getMethods()) {
				String id = index.member(cls, m.getName(from), m.getDesc(from), true);
				if (id != null) {
					matched++;
					rename(renames, id, m.getName(from), m.getName(to));
					comment(comments, id, m.getComment());
				}
			}
			for (MappingTreeView.FieldMappingView f : c.getFields()) {
				String id = index.member(cls, f.getName(from), f.getDesc(from), false);
				if (id != null) {
					matched++;
					rename(renames, id, f.getName(from), f.getName(to));
					comment(comments, id, f.getComment());
				}
			}
		}
		return new Read(format.name, renames, comments, matched, mappings);
	}

	/**
	 * Writes the renames and comments as a mapping file. Classes keep their
	 * package; ProGuard is written the way R8 writes it, readable names on the left.
	 */
	public static int write(JadxBackend jadx, Path path, String formatName, Map<String, String> renames, Map<String, String> comments)
			throws RpcException {
		MappingFormat format = WRITABLE.get(formatName);
		if (format == null) {
			throw new RpcException(ErrorCode.BAD_REQUEST, "unknown mapping format: " + formatName);
		}
		Index index = new Index(jadx);
		// Every class that is renamed, commented or holds something that is.
		Set<String> touched = new HashSet<>();
		for (String id : concat(renames.keySet(), comments.keySet())) {
			String cls = id.contains(".") ? id.substring(0, id.indexOf('.')) : id;
			if (index.classes.contains(cls)) {
				touched.add(cls);
			}
		}
		MemoryMappingTree tree = new MemoryMappingTree();
		int written = 0;
		try {
			tree.visitNamespaces(FILE_NS, List.of(NAMED_NS));
			for (String cls : new java.util.TreeSet<>(touched)) {
				tree.visitClass(cls);
				tree.visitDstName(MappedElementKind.CLASS, 0, namedClass(cls, renames));
				if (renames.containsKey(cls)) {
					written++;
				}
				if (comments.containsKey(cls)) {
					tree.visitComment(MappedElementKind.CLASS, comments.get(cls));
				}
				for (String id : index.members.getOrDefault(cls, List.of())) {
					if (!renames.containsKey(id) && !comments.containsKey(id)) {
						continue;
					}
					String member = id.substring(cls.length() + 1);
					boolean method = member.contains("(");
					String name = method ? member.substring(0, member.indexOf('(')) : member.substring(0, member.indexOf(':'));
					String desc = method ? member.substring(member.indexOf('(')) : member.substring(member.indexOf(':') + 1);
					MappedElementKind kind = method ? MappedElementKind.METHOD : MappedElementKind.FIELD;
					if (method) {
						tree.visitMethod(name, desc);
					} else {
						tree.visitField(name, desc);
					}
					tree.visitDstName(kind, 0, renames.getOrDefault(id, name));
					if (comments.containsKey(id)) {
						tree.visitComment(kind, comments.get(id));
					}
					if (renames.containsKey(id)) {
						written++;
					}
				}
			}
			tree.visitEnd();
			try (MappingWriter writer = MappingWriter.create(path, format)) {
				tree.accept(format == MappingFormat.PROGUARD_FILE ? new MappingSourceNsSwitch(writer, NAMED_NS) : writer);
			}
		} catch (IOException | RuntimeException e) {
			throw new RpcException(ErrorCode.EXPORT_FAILED, "could not write " + path.getFileName() + ": " + e.getMessage(), e);
		}
		return written;
	}

	/** {@code a/b$c} with renames applied to it and its outer classes: {@code a/Login$Form}. */
	private static String namedClass(String cls, Map<String, String> renames) {
		int slash = cls.lastIndexOf('/');
		String[] parts = cls.substring(slash + 1).split("\\$", -1);
		StringBuilder sb = new StringBuilder(cls.substring(0, slash + 1));
		String outer = cls.substring(0, slash + 1);
		for (int i = 0; i < parts.length; i++) {
			outer = i == 0 ? outer + parts[0] : outer + "$" + parts[i];
			if (i > 0) {
				sb.append('$');
			}
			sb.append(renames.getOrDefault(outer, parts[i]));
		}
		return sb.toString();
	}

	private static int bestNamespace(MemoryMappingTree tree, Set<String> classes) {
		int best = -1;
		int hits = -1;
		for (int ns = tree.getMinNamespaceId(); ns < tree.getMaxNamespaceId(); ns++) {
			int n = 0;
			for (MappingTreeView.ClassMappingView c : tree.getClasses()) {
				if (classes.contains(c.getName(ns))) {
					n++;
				}
			}
			if (n > hits) {
				best = ns;
				hits = n;
			}
		}
		return best;
	}

	/** "named" when there is one (Yarn, Mojang via Fabric), else the last namespace that isn't {@code from}. */
	private static int otherNamespace(MemoryMappingTree tree, int from) {
		int named = tree.getNamespaceId(NAMED_NS);
		if (named != MappingTreeView.NULL_NAMESPACE_ID && named != from) {
			return named;
		}
		for (int ns = tree.getMaxNamespaceId() - 1; ns >= tree.getMinNamespaceId(); ns--) {
			if (ns != from) {
				return ns;
			}
		}
		return from;
	}

	private static void rename(Map<String, String> renames, String id, String from, String to) {
		if (to != null && !to.equals(from) && !from.startsWith("<")) {
			renames.put(id, to);
		}
	}

	private static void comment(Map<String, String> comments, String id, String text) {
		if (text != null && !text.isBlank()) {
			comments.put(id, text.strip());
		}
	}

	private static String simple(String cls) {
		if (cls == null) {
			return null;
		}
		String s = cls.substring(cls.lastIndexOf('/') + 1);
		return s.substring(s.lastIndexOf('$') + 1);
	}

	private static List<String> concat(Set<String> a, Set<String> b) {
		List<String> out = new ArrayList<>(a);
		out.addAll(b);
		return out;
	}

	/** This file's class ids and each class's member ids. */
	private static final class Index {
		final Set<String> classes = new HashSet<>();
		final Map<String, List<String>> members = new LinkedHashMap<>();

		Index(JadxBackend jadx) {
			for (JavaNode node : jadx.allNodes()) {
				if (node instanceof JavaClass c) {
					classes.add(Nodes.id(c));
				} else if (node instanceof JavaMethod || node instanceof JavaField) {
					String id = Nodes.id(node);
					members.computeIfAbsent(id.substring(0, id.indexOf('.')), k -> new ArrayList<>()).add(id);
				}
			}
		}

		/** The member's id; without a descriptor, the only one of that name. */
		String member(String cls, String name, String desc, boolean method) {
			if (name == null) {
				return null;
			}
			List<String> ids = members.getOrDefault(cls, List.of());
			String prefix = cls + "." + name + (method ? "(" : ":");
			if (desc != null) {
				String id = cls + "." + name + (method ? "" : ":") + desc;
				return ids.contains(id) ? id : null;
			}
			Map<String, Integer> seen = new HashMap<>();
			String found = null;
			for (String id : ids) {
				if (id.startsWith(prefix)) {
					found = id;
					seen.merge(name, 1, Integer::sum);
				}
			}
			return seen.getOrDefault(name, 0) == 1 ? found : null;
		}
	}
}
