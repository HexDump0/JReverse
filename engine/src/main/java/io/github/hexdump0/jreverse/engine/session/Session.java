package io.github.hexdump0.jreverse.engine.session;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;

import io.github.hexdump0.jreverse.engine.backend.Backend;
import io.github.hexdump0.jreverse.engine.backend.ClassEntry;
import io.github.hexdump0.jreverse.engine.backend.JadxBackend;
import io.github.hexdump0.jreverse.engine.rpc.ErrorCode;
import io.github.hexdump0.jreverse.engine.rpc.RpcException;

/** One opened file and the decompilers loaded over it. */
public final class Session implements AutoCloseable {

	private final String id;
	private final Path path;
	private final InputKind kind;
	private final JadxBackend primary;
	private final Map<String, Backend> backends;
	private JsonObject overview;

	Session(String id, Path path, InputKind kind, JadxBackend primary) {
		this.id = id;
		this.path = path;
		this.kind = kind;
		this.primary = primary;
		this.backends = Map.of(primary.id(), primary);
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

	public Backend backend(String engine) throws RpcException {
		Backend b = backends.get(engine);
		if (b == null) {
			throw new RpcException(ErrorCode.NO_ENGINE, "engine not available: " + engine);
		}
		return b;
	}

	@Override
	public void close() {
		backends.values().forEach(Backend::close);
	}
}
