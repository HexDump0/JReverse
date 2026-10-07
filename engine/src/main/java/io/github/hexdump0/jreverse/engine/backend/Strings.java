package io.github.hexdump0.jreverse.engine.backend;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.api.JavaNode;
import jadx.api.plugins.input.data.ICodeReader;
import jadx.api.plugins.input.data.annotations.EncodedType;
import jadx.api.plugins.input.data.annotations.EncodedValue;
import jadx.api.plugins.input.data.attributes.JadxAttrType;
import jadx.api.plugins.input.data.IMethodRef;
import jadx.api.plugins.input.insns.InsnData;
import jadx.api.plugins.input.insns.Opcode;
import jadx.core.dex.nodes.MethodNode;

import io.github.hexdump0.jreverse.engine.rpc.ErrorCode;
import io.github.hexdump0.jreverse.engine.rpc.RpcException;

/**
 * Every string constant in the code and where it is used, read from the
 * bytecode's own instructions (and constant field values), so nothing is
 * decompiled. A big APK takes a second or two.
 */
public final class Strings {

	/** How many places to name per string; {@link Value#uses()} counts them all. */
	private static final int PLACES = 12;
	private static final int MAX_LENGTH = 4000;

	/**
	 * @param at   method and field ids that use it, at most {@link #PLACES}
	 * @param uses how many methods and fields use it
	 */
	public record Value(String value, int uses, List<String> at) {
	}

	private Strings() {
	}

	public static List<Value> collect(JadxBackend jadx, Progress progress, BooleanSupplier cancelled) throws RpcException {
		Map<String, List<String>> found = new LinkedHashMap<>();
		Map<String, Integer> counts = new LinkedHashMap<>();
		List<JavaNode> nodes = jadx.allNodes();
		int done = 0;
		for (JavaNode node : nodes) {
			if (++done % 500 == 0) {
				if (cancelled.getAsBoolean()) {
					throw new RpcException(ErrorCode.CANCELLED, "cancelled");
				}
				progress.report(done, nodes.size());
			}
			if (node instanceof JavaMethod m) {
				String id = Nodes.id(m);
				for (String s : constants(m.getMethodNode())) {
					add(found, counts, s, id);
				}
			} else if (node instanceof JavaField f) {
				EncodedValue v = f.getFieldNode().get(JadxAttrType.CONSTANT_VALUE);
				if (v != null && v.getType() == EncodedType.ENCODED_STRING && v.getValue() instanceof String s) {
					add(found, counts, s, Nodes.id(f));
				}
			}
		}
		progress.report(nodes.size(), nodes.size());
		List<Value> out = new ArrayList<>(found.size());
		for (Map.Entry<String, List<String>> e : found.entrySet()) {
			out.add(new Value(e.getKey(), counts.get(e.getKey()), e.getValue()));
		}
		return out;
	}

	/** The distinct strings one method loads; empty for abstract and native methods. */
	private static List<String> constants(MethodNode mth) {
		List<String> out = new ArrayList<>();
		ICodeReader code;
		try {
			code = mth.getCodeReader();
		} catch (Exception e) {
			return out;
		}
		if (code == null) {
			return out;
		}
		String[] pending = {null};
		try {
			code.visitInstructions(insn -> {
				// Kotlin passes a parameter or expression name to its null checks right after loading
				// it; those strings are the compiler's, not the program's.
				if (pending[0] != null) {
					String s = pending[0];
					pending[0] = null;
					if (!isNullCheck(insn)) {
						keep(out, s);
					}
				}
				// DEX has const-string; class files an ldc whose type is only known once decoded.
				if (insn.getOpcode() == Opcode.CONST_STRING || insn.getOpcodeMnemonic().startsWith("ldc")) {
					insn.decode();
					if (insn.getOpcode() == Opcode.CONST_STRING) {
						pending[0] = insn.getIndexAsString();
					}
				}
			});
		} catch (Exception e) {
			// A method jadx can't read gives what was found before it failed.
		}
		if (pending[0] != null) {
			keep(out, pending[0]);
		}
		return out;
	}

	private static void keep(List<String> out, String s) {
		if (s != null && !out.contains(s)) {
			out.add(s);
		}
	}

	private static boolean isNullCheck(InsnData insn) {
		Opcode op = insn.getOpcode();
		if (op != Opcode.INVOKE_STATIC && op != Opcode.INVOKE_STATIC_RANGE) {
			return false;
		}
		insn.decode();
		IMethodRef ref = insn.getIndexAsMethod();
		ref.load();
		return "Lkotlin/jvm/internal/Intrinsics;".equals(ref.getParentClassType()) && ref.getName().startsWith("check");
	}

	private static void add(Map<String, List<String>> found, Map<String, Integer> counts, String s, String id) {
		if (s.isEmpty()) {
			return;
		}
		String value = s.length() > MAX_LENGTH ? s.substring(0, MAX_LENGTH) : s;
		List<String> at = found.computeIfAbsent(value, k -> new ArrayList<>(2));
		counts.merge(value, 1, Integer::sum);
		if (at.size() < PLACES) {
			at.add(id);
		}
	}
}
