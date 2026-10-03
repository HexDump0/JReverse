package io.github.hexdump0.jreverse.engine.session;

import java.nio.file.Path;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonObject;

import io.github.hexdump0.jreverse.engine.backend.Backend;
import io.github.hexdump0.jreverse.engine.backend.ClassEntry;
import io.github.hexdump0.jreverse.engine.backend.JadxBackend;
import io.github.hexdump0.jreverse.engine.backend.VineflowerBackend;
import io.github.hexdump0.jreverse.engine.rpc.ErrorCode;
import io.github.hexdump0.jreverse.engine.rpc.RpcException;

/** One opened file and the decompilers loaded over it. */
public final class Session implements AutoCloseable {

	private final String id;
	private final Path path;
	private final InputKind kind;
	private final JadxBackend primary;
	/** Other decompilers, loaded on first use. */
	private final Map<String, Backend> others = new HashMap<>();
	private final boolean deobfuscated;
	private JsonObject overview;

	Session(String id, Path path, InputKind kind, JadxBackend primary, boolean deobfuscated) {
		this.id = id;
		this.path = path;
		this.kind = kind;
		this.primary = primary;
		this.deobfuscated = deobfuscated;
	}

	/** Whether jadx generated names for short and clashing identifiers. */
	public boolean deobfuscated() {
		return deobfuscated;
	}

	public String id() {
		return id;
	}

	public Path path() {
		return path;
	}

	public InputKind kind() {
		return kind;
	}

	/** Class list as seen by the primary backend. */
	public List<ClassEntry> classes() {
		return primary.classes();
	}

	/** jadx, which also answers everything beyond plain decompiling: links, smali, usages, search. */
	public JadxBackend jadx() {
		return primary;
	}

	public synchronized JsonObject overview() {
		if (overview == null) {
			overview = Overview.build(this);
		}
		return overview;
	}

	/** The decompilers that can read this input; jadx first. Vineflower reads JVM class files only. */
	public List<String> engines() {
		return switch (kind) {
			case JAR, AAR, CLASS -> List.of(JadxBackend.ID, VineflowerBackend.ID);
			default -> List.of(JadxBackend.ID);
		};
	}

	public synchronized Backend backend(String engine) throws RpcException {
		if (engine.equals(primary.id())) {
			return primary;
		}
		if (!engines().contains(engine)) {
			throw new RpcException(ErrorCode.NO_ENGINE, "engine not available for " + kind.wireName() + " input: " + engine);
		}
		Backend b = others.get(engine);
		if (b == null) {
			b = VineflowerBackend.load(path, kind == InputKind.AAR, kind == InputKind.CLASS ? VineflowerBackend.singleId(classes()) : null,
					classes());
			others.put(engine, b);
		}
		return b;
	}

	@Override
	public synchronized void close() {
		primary.close();
		others.values().forEach(Backend::close);
	}
}
