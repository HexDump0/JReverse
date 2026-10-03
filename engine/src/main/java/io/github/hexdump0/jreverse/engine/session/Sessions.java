package io.github.hexdump0.jreverse.engine.session;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.hexdump0.jreverse.engine.backend.JadxBackend;
import io.github.hexdump0.jreverse.engine.backend.VineflowerBackend;
import io.github.hexdump0.jreverse.engine.rpc.ErrorCode;
import io.github.hexdump0.jreverse.engine.rpc.RpcException;

/** Registry of open sessions. Thread-safe. */
public final class Sessions {

	public static final List<String> ENGINES = List.of(JadxBackend.ID, VineflowerBackend.ID);
	public static final String DEFAULT_ENGINE = JadxBackend.ID;

	private final Map<String, Session> open = new ConcurrentHashMap<>();
	private final AtomicInteger next = new AtomicInteger();

	public Session open(Path path) throws RpcException {
		InputKind kind = InputDetector.detect(path);
		JadxBackend jadx = JadxBackend.load(path);
		Session session = new Session("s" + next.incrementAndGet(), path, kind, jadx);
		open.put(session.id(), session);
		return session;
	}

	public Session get(String id) throws RpcException {
		Session s = open.get(id);
		if (s == null) {
			throw new RpcException(ErrorCode.NO_SESSION, "no such session: " + id);
		}
		return s;
	}

	public void close(String id) throws RpcException {
		Session s = open.remove(id);
		if (s == null) {
			throw new RpcException(ErrorCode.NO_SESSION, "no such session: " + id);
		}
		s.close();
	}

	public void closeAll() {
		open.values().removeIf(s -> {
			s.close();
			return true;
		});
	}
}
