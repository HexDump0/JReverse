package io.github.hexdump0.jreverse.engine.backend;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.api.JavaNode;
import jadx.core.codegen.TypeGen;
import jadx.core.dex.info.AccessInfo;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.nodes.FieldNode;
import jadx.core.dex.nodes.MethodNode;

import io.github.hexdump0.jreverse.engine.rpc.ErrorCode;
import io.github.hexdump0.jreverse.engine.rpc.RpcException;

/**
 * Wire ids for jadx's nodes. A class is {@code com/foo/Bar$Inner}, a method
 * {@code com/foo/Bar.run(I)V} and a field {@code com/foo/Bar.count:I}, all
 * from original names so they survive renames.
 */
final class Nodes {

	private final JadxDecompiler jadx;
	private final Map<String, JavaClass> classes;
	private final List<JavaClass> sorted;

	Nodes(JadxDecompiler jadx) {
		this.jadx = jadx;
		Map<String, JavaClass> map = new HashMap<>();
		for (JavaClass cls : jadx.getClassesWithInners()) {
			map.putIfAbsent(id(cls), cls);
		}
		this.classes = Map.copyOf(map);
		this.sorted = map.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).toList();
	}

	static String id(JavaNode node) {
		return switch (node) {
			case JavaClass c -> c.getClassNode().getClassInfo().getRawName().replace('.', '/');
			case JavaMethod m -> id(m.getDeclaringClass()) + "." + m.getMethodNode().getMethodInfo().getShortId();
			case JavaField f -> id(f.getDeclaringClass()) + "." + f.getFieldNode().getFieldInfo().getShortId();
			default -> throw new IllegalArgumentException("not a class, method or field: " + node);
		};
	}

	/** The top-level class whose source holds {@code node}. */
	static JavaClass top(JavaNode node) {
		JavaClass cls = node instanceof JavaClass c ? c : node.getDeclaringClass();
		return cls.getTopParentClass();
	}

	JavaClass cls(String id) throws RpcException {
		JavaClass cls = classes.get(id);
		if (cls == null) {
			throw new RpcException(ErrorCode.NO_CLASS, "class not found: " + id);
		}
		return cls;
	}

	JavaNode node(String id) throws RpcException {
		int dot = id.indexOf('.');
		if (dot < 0) {
			return cls(id);
		}
		JavaClass cls = cls(id.substring(0, dot));
		String member = id.substring(dot + 1);
		if (member.contains("(")) {
			JavaMethod m = cls.searchMethodByShortId(member);
			if (m != null) {
				return m;
			}
		} else {
			// Through the ClassNode: JavaClass.getFields() decompiles the class and hides enum constants.
			FieldNode f = cls.getClassNode().searchFieldByShortId(member);
			if (f != null && jadx.getJavaNodeByRef(f) instanceof JavaField field) {
				return field;
			}
		}
		throw new RpcException(ErrorCode.NO_NODE, "not found: " + id);
	}

	/**
	 * Every class, method and field, inner classes included, without decompiling
	 * anything. Synthetic members are left out.
	 */
	List<JavaNode> all() {
		List<JavaNode> out = new ArrayList<>();
		for (JavaClass cls : sorted) {
			out.add(cls);
			for (MethodNode m : cls.getClassNode().getMethods()) {
				if (!m.getAccessFlags().isSynthetic() && jadx.getJavaNodeByRef(m) instanceof JavaMethod jm) {
					out.add(jm);
				}
			}
			for (FieldNode f : cls.getClassNode().getFields()) {
				if (!f.getAccessFlags().isSynthetic() && jadx.getJavaNodeByRef(f) instanceof JavaField jf) {
					out.add(jf);
				}
			}
		}
		return out;
	}

	/** A class's constructors, without decompiling it. */
	List<JavaMethod> constructors(JavaClass cls) {
		List<JavaMethod> out = new ArrayList<>();
		for (MethodNode m : cls.getClassNode().getMethods()) {
			if (m.getMethodInfo().isConstructor() && jadx.getJavaNodeByRef(m) instanceof JavaMethod jm) {
				out.add(jm);
			}
		}
		return out;
	}

	static boolean isNode(JavaNode node) {
		return node instanceof JavaClass || node instanceof JavaMethod || node instanceof JavaField;
	}

	static NodeInfo info(JavaNode node) {
		String top = id(top(node));
		return switch (node) {
			case JavaClass c -> new NodeInfo("class", id(c), top, c.getName(), c.getFullName(), access(c.getAccessInfo()),
					c.getAccessInfo().isStatic(), List.of());
			case JavaMethod m -> {
				MethodInfo mi = m.getMethodNode().getMethodInfo();
				String name = m.isConstructor() ? m.getDeclaringClass().getName() : m.getName();
				String args = mi.getArgumentsTypes().stream().map(Nodes::shortType).collect(Collectors.joining(", "));
				String detail = m.isConstructor() || m.isClassInit() ? name + "(" + args + ")"
						: name + "(" + args + "): " + shortType(mi.getReturnType());
				List<String> frida = new ArrayList<>();
				mi.getArgumentsTypes().forEach(t -> frida.add(fridaType(t)));
				yield new NodeInfo("method", id(m), top, name, detail, access(m.getAccessFlags()), m.getAccessFlags().isStatic(),
						frida);
			}
			case JavaField f -> new NodeInfo("field", id(f), top, f.getName(), f.getName() + ": " + shortType(f.getType()),
					access(f.getAccessFlags()), f.getAccessFlags().isStatic(), List.of());
			default -> throw new IllegalArgumentException("not a class, method or field: " + node);
		};
	}

	private static String access(AccessInfo a) {
		return a.isPublic() ? "public" : a.isProtected() ? "protected" : a.isPrivate() ? "private" : "";
	}

	/** {@code String}, {@code int[]}, {@code Map}: the simple name, without generics. */
	static String shortType(ArgType t) {
		if (t.isArray()) {
			return shortType(t.getArrayElement()) + "[]";
		}
		if (t.isPrimitive()) {
			return t.getPrimitiveType().getLongName();
		}
		if (t.isObject()) {
			String name = t.getObject();
			name = name.substring(name.lastIndexOf('.') + 1);
			return name.substring(name.lastIndexOf('$') + 1);
		}
		return t.toString();
	}

	/** Frida's spelling: {@code int}, {@code java.lang.String}, {@code [B}, {@code [Ljava.lang.String;}. */
	static String fridaType(ArgType t) {
		if (t.isPrimitive()) {
			return t.getPrimitiveType().getLongName();
		}
		String sig = TypeGen.signature(t);
		if (t.isArray()) {
			return sig.replace('/', '.');
		}
		if (sig.startsWith("L") && sig.endsWith(";")) {
			return sig.substring(1, sig.length() - 1).replace('/', '.');
		}
		return t.toString();
	}
}
