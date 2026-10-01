package io.github.hexdump0.jreverse.engine.backend;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
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

	private JadxBackend(JadxDecompiler jadx, List<ClassEntry> classes, Map<String, JavaClass> byId) {
		this.jadx = jadx;
		this.classes = classes;
		this.byId = byId;
	}

	public static JadxBackend load(Path input) throws RpcException {
		JadxArgs args = new JadxArgs();
		args.setInputFile(input.toFile());
		args.setPluginLoader(new JadxBasePluginLoader());
		args.setSkipResources(true); // resources come with the Overview page
		args.setDeobfuscationOn(false); // renames are JReverse's job, keyed by original names
		args.setShowInconsistentCode(true); // partial output beats none

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
			String id = cls.getClassNode().getClassInfo().getRawName().replace('.', '/');
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
		JavaClass cls = byId.get(classId);
		if (cls == null) {
			throw new RpcException(ErrorCode.NO_CLASS, "class not found: " + classId);
		}
		String code;
		try {
			// jadx caches the result in its in-memory code cache.
			code = cls.getCode();
		} catch (Exception | StackOverflowError e) {
			throw new RpcException(ErrorCode.DECOMPILE_FAILED, "jadx failed on " + classId + ": " + e, e);
		}
		return new Decompiled(code, countWarnings(code));
	}

	@Override
	public void close() {
		jadx.close();
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
}
