package io.github.hexdump0.jreverse.engine.rpc;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.github.hexdump0.jreverse.engine.backend.Backend;
import io.github.hexdump0.jreverse.engine.backend.ClassEntry;
import io.github.hexdump0.jreverse.engine.backend.Decompiled;
import io.github.hexdump0.jreverse.engine.session.Session;
import io.github.hexdump0.jreverse.engine.session.Sessions;

/** Request handlers. Each takes the request's params and returns its result. */
final class Methods {

	private final Sessions sessions;

	Methods(Sessions sessions) {
		this.sessions = sessions;
	}

	JsonElement open(JsonObject params) throws RpcException {
		String raw = string(params, "path");
		Path path;
		try {
			path = Path.of(raw).toAbsolutePath().normalize();
		} catch (InvalidPathException e) {
			throw new RpcException(ErrorCode.BAD_REQUEST, "invalid path: " + raw);
		}
		if (!Files.isRegularFile(path)) {
			throw new RpcException(ErrorCode.OPEN_FAILED, "no such file: " + path);
		}
		long start = System.nanoTime();
		Session session = sessions.open(path);
		JsonObject result = new JsonObject();
		result.addProperty("session", session.id());
		result.addProperty("kind", session.kind().wireName());
		result.addProperty("classCount", session.classes().size());
		result.addProperty("ms", millisSince(start));
		return result;
	}

	JsonElement listClasses(JsonObject params) throws RpcException {
		Session session = sessions.get(string(params, "session"));
		JsonArray list = new JsonArray(session.classes().size());
		for (ClassEntry cls : session.classes()) {
			JsonObject o = new JsonObject();
			o.addProperty("id", cls.id());
			o.addProperty("kind", cls.kind().wireName());
			list.add(o);
		}
		return list;
	}

	JsonElement decompile(JsonObject params) throws RpcException {
		Session session = sessions.get(string(params, "session"));
		String classId = string(params, "class");
		String engine = params.has("engine") ? string(params, "engine") : Sessions.DEFAULT_ENGINE;
		Backend backend = session.backend(engine);
		long start = System.nanoTime();
		Decompiled out = backend.decompile(classId);
		JsonObject result = new JsonObject();
		result.addProperty("source", out.source());
		result.addProperty("engine", backend.id());
		result.addProperty("ms", millisSince(start));
		result.addProperty("warnings", out.warnings());
		return result;
	}

	JsonElement close(JsonObject params) throws RpcException {
		sessions.close(string(params, "session"));
		return new JsonObject();
	}

	private static String string(JsonObject params, String name) throws RpcException {
		JsonElement el = params.get(name);
		if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
			throw new RpcException(ErrorCode.BAD_REQUEST, "missing string param: " + name);
		}
		return el.getAsString();
	}

	private static long millisSince(long startNanos) {
		return (System.nanoTime() - startNanos) / 1_000_000;
	}
}
