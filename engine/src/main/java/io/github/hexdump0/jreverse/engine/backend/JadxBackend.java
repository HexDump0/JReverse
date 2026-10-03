package io.github.hexdump0.jreverse.engine.backend;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import jadx.api.ICodeInfo;
import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaMethod;
import jadx.api.JavaNode;
import jadx.api.ResourceFile;
import jadx.api.ResourceType;
import jadx.api.data.ICodeComment;
import jadx.api.data.ICodeRename;
import jadx.api.data.impl.JadxCodeComment;
import jadx.api.data.impl.JadxCodeData;
import jadx.api.data.impl.JadxCodeRename;
import jadx.api.data.impl.JadxNodeRef;
import jadx.api.metadata.ICodeAnnotation;
import jadx.api.metadata.ICodeNodeRef;
import jadx.api.metadata.annotations.NodeDeclareRef;
import jadx.api.plugins.loader.JadxBasePluginLoader;
import jadx.core.dex.info.AccessInfo;
import jadx.core.dex.instructions.args.ArgType;

import io.github.hexdump0.jreverse.engine.rpc.ErrorCode;
import io.github.hexdump0.jreverse.engine.rpc.RpcException;

/** JADX reads DEX and class files directly, so it works for every input kind. */
public final class JadxBackend implements Backend {

	public static final String ID = "jadx";

	private final JadxDecompiler jadx;
	private final List<ClassEntry> classes;
	private final Map<String, JavaClass> byId;
	private final Nodes nodes;
	private final ArchiveFiles files;
	// Renames and comments re-run jadx's passes over every class, so nothing may
	// decompile while they are applied.
	private final ReadWriteLock codeData = new ReentrantReadWriteLock();
	/** Nodes renamed by the last {@link #setCodeData}; jadx keeps an alias until told to drop it. */
	private Set<JavaNode> renamed = Set.of();

	private JadxBackend(JadxDecompiler jadx, List<ClassEntry> classes, Map<String, JavaClass> byId) {
		this.jadx = jadx;
		this.classes = classes;
		this.byId = byId;
		this.nodes = new Nodes(jadx);
		this.files = new ArchiveFiles(jadx);
	}

	public static JadxBackend load(Path input) throws RpcException {
		JadxArgs args = new JadxArgs();
		args.setInputFile(input.toFile());
		args.setPluginLoader(new JadxBasePluginLoader());
		args.setSkipResources(true); // only the manifest is decoded, on demand
		args.setDeobfuscationOn(false); // renames are JReverse's job, keyed by original names
		args.setShowInconsistentCode(true); // partial output beats none
		args.setCodeData(new JadxCodeData());

		JadxDecompiler jadx = new JadxDecompiler(args);
		try {
			jadx.load();
		} catch (Exception e) {
			jadx.close();
			throw new RpcException(ErrorCode.OPEN_FAILED, "jadx could not load " + input.getFileName() + ": " + e.getMessage(), e);
		}

		List<JavaClass> javaClasses = jadx.getClasses();
		if (javaClasses.isEmpty()) {
			jadx.close();
			throw new RpcException(ErrorCode.OPEN_FAILED, "no classes found in " + input.getFileName());
		}
		List<ClassEntry> entries = new ArrayList<>(javaClasses.size());
		Map<String, JavaClass> byId = new HashMap<>(javaClasses.size() * 2);
		for (JavaClass cls : javaClasses) {
			String id = Nodes.id(cls);
			if (byId.putIfAbsent(id, cls) == null) {
				entries.add(new ClassEntry(id, kindOf(cls)));
			}
		}
		entries.sort(Comparator.comparing(ClassEntry::id));
		return new JadxBackend(jadx, List.copyOf(entries), Map.copyOf(byId));
	}

	@Override
	public String id() {
		return ID;
	}

	@Override
	public List<ClassEntry> classes() {
		return classes;
	}

	@Override
	public Decompiled decompile(String classId) throws RpcException {
		JavaClass cls = topClass(classId);
		codeData.readLock().lock();
		try {
			ICodeInfo info = codeInfo(cls);
			String code = info.getCodeStr();
			return links(code, info);
		} finally {
			codeData.readLock().unlock();
		}
	}

	/** Plain source, for search and export. */
	public String source(String classId) throws RpcException {
		codeData.readLock().lock();
		try {
			return codeInfo(topClass(classId)).getCodeStr();
		} finally {
			codeData.readLock().unlock();
		}
	}

	/** Smali for DEX input, JVM bytecode in the same assembler style for class files. */
	public String smali(String classId) throws RpcException {
		JavaClass cls = topClass(classId);
		try {
			return cls.getSmali();
		} catch (Exception | StackOverflowError e) {
			throw new RpcException(ErrorCode.DECOMPILE_FAILED, "no bytecode for " + classId + ": " + e, e);
		}
	}

	public NodeInfo nodeInfo(String nodeId) throws RpcException {
		return Nodes.info(nodes.node(nodeId));
	}

	/**
	 * Every place in the code that uses {@code nodeId}: for methods including
	 * overrides and overridden ones, for classes including constructor calls.
	 */
	public List<Usage> usages(String nodeId) throws RpcException {
		JavaNode target = nodes.node(nodeId);
		Set<JavaNode> targets = new LinkedHashSet<>();
		targets.add(target);
		if (target instanceof JavaMethod m) {
			targets.addAll(m.getOverrideRelatedMethods());
		} else if (target instanceof JavaClass c) {
			// "new Foo()" is a use of Foo, but jadx records it against the constructor.
			targets.addAll(nodes.constructors(c));
		}
		codeData.readLock().lock();
		try {
			// Group the users by the source file they live in, so each is decompiled once.
			Map<JavaClass, Set<JavaNode>> byTop = new LinkedHashMap<>();
			for (JavaNode t : targets) {
				for (JavaNode user : t.getUseIn()) {
					byTop.computeIfAbsent(Nodes.top(user), k -> new LinkedHashSet<>()).add(t);
				}
			}
			List<Usage> out = new ArrayList<>();
			for (Map.Entry<JavaClass, Set<JavaNode>> e : byTop.entrySet()) {
				JavaClass top = e.getKey();
				ICodeInfo info = codeInfo(top);
				String code = info.getCodeStr();
				Lines lines = new Lines(code);
				Set<Integer> seen = new HashSet<>();
				for (JavaNode t : e.getValue()) {
					for (int pos : top.getUsePlacesFor(info, t)) {
						if (!seen.add(pos)) {
							continue;
						}
						JavaNode in = jadx.getEnclosingNode(info, pos);
						int line = lines.line(pos);
						out.add(new Usage(Nodes.id(top), line, pos - lines.start(line), identLength(code, pos, t),
								lines.text(line), in != null && Nodes.isNode(in) ? Nodes.info(in) : null));
					}
				}
			}
			out.sort(Comparator.comparing(Usage::cls).thenComparingInt(Usage::line).thenComparingInt(Usage::col));
			return out;
		} finally {
			codeData.readLock().unlock();
		}
	}

	/**
	 * Replaces all renames and comments, then drops decompiled code so the next
	 * request sees them. Unknown node ids are skipped; returns how many applied.
	 */
	public int setCodeData(Map<String, String> renames, Map<String, String> comments) {
		codeData.writeLock().lock();
		try {
			List<ICodeRename> rn = new ArrayList<>();
			Set<JavaNode> now = new HashSet<>();
			int applied = 0;
			for (Map.Entry<String, String> e : renames.entrySet()) {
				JavaNode node = nodeOrNull(e.getKey());
				if (node != null) {
					rn.add(new JadxCodeRename(JadxNodeRef.forJavaNode(node), e.getValue()));
					now.add(node);
					applied++;
				}
			}
			for (JavaNode node : renamed) {
				if (!now.contains(node)) {
					node.removeAlias();
				}
			}
			renamed = now;
			List<ICodeComment> cm = new ArrayList<>();
			for (Map.Entry<String, String> e : comments.entrySet()) {
				JavaNode node = nodeOrNull(e.getKey());
				if (node != null) {
					cm.add(new JadxCodeComment(JadxNodeRef.forJavaNode(node), e.getValue()));
					applied++;
				}
			}
			JadxCodeData data = new JadxCodeData();
			data.setRenames(rn);
			data.setComments(cm);
			jadx.getArgs().setCodeData(data);
			jadx.reloadCodeData();
			// What ClassNode.reloadCode does, minus the decompile: that happens on the next request.
			for (JavaClass cls : jadx.getClasses()) {
				cls.getClassNode().unloadFromCache();
				cls.getClassNode().deepUnload();
			}
			return applied;
		} finally {
			codeData.writeLock().unlock();
		}
	}

	/** Every class, method and field, inner classes included; for name search. Decompiles nothing. */
	public List<JavaNode> allNodes() {
		return nodes.all();
	}

	/** Resources, assets and other files that aren't code. */
	public ArchiveFiles files() {
		return files;
	}

	/** The decoded AndroidManifest.xml, or null for anything but an APK or AAR. */
	public String manifest() {
		for (ResourceFile res : jadx.getResources()) {
			if (res.getType() == ResourceType.MANIFEST) {
				try {
					return res.loadContent().getText().getCodeStr();
				} catch (Exception e) {
					System.err.println("engine: manifest failed to decode: " + e);
					return null;
				}
			}
		}
		return null;
	}

	@Override
	public void close() {
		jadx.close();
	}

	private JavaClass topClass(String classId) throws RpcException {
		JavaClass cls = byId.get(classId);
		if (cls == null) {
			cls = nodes.cls(classId).getTopParentClass();
		}
		return cls;
	}

	private JavaNode nodeOrNull(String id) {
		try {
			return nodes.node(id);
		} catch (RpcException e) {
			return null;
		}
	}

	private static ICodeInfo codeInfo(JavaClass cls) throws RpcException {
		try {
			// jadx caches the result in its in-memory code cache.
			return cls.getCodeInfo();
		} catch (Exception | StackOverflowError e) {
			throw new RpcException(ErrorCode.DECOMPILE_FAILED, "jadx failed on " + Nodes.id(cls) + ": " + e, e);
		}
	}

	private Decompiled links(String code, ICodeInfo info) {
		Lines lines = new Lines(code);
		Map<JavaNode, Integer> index = new HashMap<>();
		List<NodeInfo> table = new ArrayList<>();
		List<Decompiled.Span> links = new ArrayList<>();
		List<Decompiled.Span> decls = new ArrayList<>();
		List<Map.Entry<Integer, ICodeAnnotation>> anns = new ArrayList<>(info.getCodeMetadata().getAsMap().entrySet());
		anns.sort(Map.Entry.comparingByKey());
		for (Map.Entry<Integer, ICodeAnnotation> e : anns) {
			int pos = e.getKey();
			ICodeAnnotation ann = e.getValue();
			boolean decl = ann instanceof NodeDeclareRef;
			ICodeNodeRef ref = decl ? ((NodeDeclareRef) ann).getNode() : ann instanceof ICodeNodeRef r ? r : null;
			if (ref == null || pos >= code.length()) {
				continue;
			}
			JavaNode node = jadx.getJavaNodeByRef(ref);
			if (node == null || !Nodes.isNode(node)) {
				continue;
			}
			int len = identLength(code, pos, node);
			if (len == 0) {
				continue;
			}
			Integer n = index.get(node);
			if (n == null) {
				n = table.size();
				index.put(node, n);
				table.add(Nodes.info(node));
			}
			int line = lines.line(pos);
			Decompiled.Span span = new Decompiled.Span(line, pos - lines.start(line), len, n);
			links.add(span);
			if (decl) {
				decls.add(span);
			}
		}
		return new Decompiled(code, countWarnings(code), links, decls, table);
	}

	/** The identifier at {@code pos}; a fully qualified class name counts as one. */
	private static int identLength(String code, int pos, JavaNode node) {
		if (node instanceof JavaClass cls && code.startsWith(cls.getFullName(), pos)) {
			return cls.getFullName().length();
		}
		int end = pos;
		while (end < code.length() && Character.isJavaIdentifierPart(code.charAt(end))) {
			end++;
		}
		return end - pos;
	}

	private static ClassKind kindOf(JavaClass cls) {
		AccessInfo acc = cls.getAccessInfo();
		if (acc.isAnnotation()) {
			return ClassKind.ANNOTATION;
		}
		if (acc.isInterface()) {
			return ClassKind.INTERFACE;
		}
		if (acc.isEnum()) {
			return ClassKind.ENUM;
		}
		ArgType sup = cls.getClassNode().getSuperClass();
		if (sup != null && sup.isObject() && "java.lang.Record".equals(sup.getObject())) {
			return ClassKind.RECORD;
		}
		return ClassKind.CLASS;
	}

	/** jadx marks trouble inline as {@code JADX WARN: ...} / {@code JADX ERROR: ...} comments. */
	static int countWarnings(String code) {
		return count(code, "JADX WARN") + count(code, "JADX ERROR");
	}

	private static int count(String haystack, String needle) {
		int n = 0;
		for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
			n++;
		}
		return n;
	}

	/**
	 * One place that uses a node.
	 *
	 * @param cls  top-level class whose source contains it
	 * @param text the whole line
	 * @param in   the method, field or class the use sits in
	 */
	public record Usage(String cls, int line, int col, int len, String text, NodeInfo in) {
	}
}
