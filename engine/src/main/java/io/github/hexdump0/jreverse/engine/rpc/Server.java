package io.github.hexdump0.jreverse.engine.rpc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import io.github.hexdump0.jreverse.engine.Version;
import io.github.hexdump0.jreverse.engine.session.Sessions;

/**
 * Reads requests line by line and answers them on a small thread pool, so a
 * slow decompile never blocks other requests. Returns from {@link #run()} on
 * EOF or after answering {@code shutdown}.
 */
public final class Server {

	@FunctionalInterface
	interface Handler {
		JsonElement handle(JsonObject params) throws RpcException;
	}

	private final InputStream in;
	private final Transport transport;
	private final Sessions sessions = new Sessions();
	private final ExecutorService pool;
	private final Map<String, Handler> handlers;

	public Server(InputStream in, OutputStream out) {
		this.in = in;
		this.transport = new Transport(out);
		int threads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
		AtomicInteger n = new AtomicInteger();
		this.pool = Executors.newFixedThreadPool(threads, r -> {
			Thread t = new Thread(r, "engine-worker-" + n.incrementAndGet());
			t.setDaemon(true);
			return t;
		});
		Methods methods = new Methods(sessions);
		this.handlers = Map.of(
				"open", methods::open,
				"listClasses", methods::listClasses,
				"decompile", methods::decompile,
				"close", methods::close);
	}

	public void run() {
		JsonObject ready = new JsonObject();
		ready.addProperty("protocol", Version.PROTOCOL);
		ready.addProperty("version", Version.engine());
		JsonArray engines = new JsonArray();
		Sessions.ENGINES.forEach(engines::add);
		ready.add("engines", engines);
		transport.notify("ready", ready);

		try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (!line.isBlank() && !dispatch(line)) {
					return;
				}
			}
		} catch (IOException e) {
			System.err.println("engine: stdin failed: " + e);
		} finally {
			stop();
		}
	}

	/** Returns false when the server should stop reading. */
	private boolean dispatch(String line) {
		JsonObject msg;
		try {
			JsonElement parsed = JsonParser.parseString(line);
			if (!parsed.isJsonObject()) {
				transport.error(null, ErrorCode.BAD_REQUEST, "request must be a JSON object");
				return true;
			}
			msg = parsed.getAsJsonObject();
		} catch (JsonParseException e) {
			transport.error(null, ErrorCode.BAD_REQUEST, "malformed JSON: " + e.getMessage());
			return true;
		}

		JsonElement id = msg.get("id");
		if (id == null || !id.isJsonPrimitive() || id.getAsJsonPrimitive().isBoolean()) {
			transport.error(null, ErrorCode.BAD_REQUEST, "missing or invalid id");
			return true;
		}
		JsonElement methodEl = msg.get("method");
		if (methodEl == null || !methodEl.isJsonPrimitive() || !methodEl.getAsJsonPrimitive().isString()) {
			transport.error(id, ErrorCode.BAD_REQUEST, "missing method");
			return true;
		}
		String method = methodEl.getAsString();
		JsonElement paramsEl = msg.get("params");
		JsonObject params;
		if (paramsEl == null || paramsEl instanceof JsonNull) {
			params = new JsonObject();
		} else if (paramsEl.isJsonObject()) {
			params = paramsEl.getAsJsonObject();
		} else {
			transport.error(id, ErrorCode.BAD_REQUEST, "params must be an object");
			return true;
		}

		if (method.equals("shutdown")) {
			stop();
			transport.result(id, new JsonObject());
			return false;
		}
		Handler handler = handlers.get(method);
		if (handler == null) {
			transport.error(id, ErrorCode.UNKNOWN_METHOD, "unknown method: " + method);
			return true;
		}
		JsonPrimitive reqId = id.getAsJsonPrimitive();
		pool.execute(() -> invoke(reqId, method, handler, params));
		return true;
	}

	private void invoke(JsonPrimitive id, String method, Handler handler, JsonObject params) {
		try {
			transport.result(id, handler.handle(params));
		} catch (RpcException e) {
			transport.error(id, e.code(), e.getMessage());
		} catch (Throwable t) {
			System.err.println("engine: " + method + " failed");
			t.printStackTrace(System.err);
			transport.error(id, ErrorCode.INTERNAL, t.getClass().getSimpleName() + ": " + t.getMessage());
		}
	}

	private void stop() {
		pool.shutdown();
		try {
			if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
				pool.shutdownNow();
			}
		} catch (InterruptedException e) {
			pool.shutdownNow();
			Thread.currentThread().interrupt();
		}
		sessions.closeAll();
	}
}
