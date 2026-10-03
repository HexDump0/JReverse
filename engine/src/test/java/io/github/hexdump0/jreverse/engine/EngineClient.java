package io.github.hexdump0.jreverse.engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/** Drives an engine child process over the real protocol. */
public final class EngineClient implements AutoCloseable {

	private final Process process;
	private final Writer in;
	private final BufferedReader out;
	private final Map<Long, JsonObject> early = new HashMap<>();
	private final List<JsonObject> notifications = new ArrayList<>();
	private final JsonObject ready;
	private long nextId;

	private EngineClient(List<String> command) throws IOException {
		process = new ProcessBuilder(command)
				.redirectError(ProcessBuilder.Redirect.INHERIT)
				.start();
		in = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
		out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
		ready = readMessage();
	}

	/** The engine on the test classpath, run by the JVM running the tests. */
	public static EngineClient fromClasspath() throws IOException {
		Path java = Path.of(System.getProperty("java.home"), "bin", "java");
		return new EngineClient(List.of(java.toString(), "-Xss8m", "-cp", System.getProperty("java.class.path"),
				Main.class.getName()));
	}

	/** The shipped engine: bundled runtime plus fat jar. */
	public static EngineClient fromDist(Path dist) throws IOException {
		String exe = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
		Path java = dist.resolve("runtime").resolve("bin").resolve(exe);
		return new EngineClient(List.of(java.toString(), "-Xss8m", "-jar", dist.resolve("engine.jar").toString()));
	}

	public JsonObject ready() {
		return ready;
	}

	public JsonObject call(String method, Object... params) throws IOException {
		return await(send(method, params));
	}

	/**
	 * Sends a request and returns its id without waiting. {@code params} alternate
	 * name, value; values are strings, booleans, numbers or JSON.
	 */
	public long send(String method, Object... params) throws IOException {
		JsonObject p = new JsonObject();
		for (int i = 0; i < params.length; i += 2) {
			p.add((String) params[i], switch (params[i + 1]) {
				case JsonElement j -> j;
				case Boolean b -> new JsonPrimitive(b);
				case Number n -> new JsonPrimitive(n);
				case Object o -> new JsonPrimitive(o.toString());
			});
		}
		JsonObject msg = new JsonObject();
		long id = ++nextId;
		msg.addProperty("id", id);
		msg.addProperty("method", method);
		msg.add("params", p);
		sendRaw(msg.toString());
		return id;
	}

	/** Notifications seen while waiting for responses, oldest first. */
	public List<JsonObject> notifications() {
		return notifications;
	}

	public void sendRaw(String line) throws IOException {
		in.write(line);
		in.write('\n');
		in.flush();
	}

	public JsonObject await(long id) throws IOException {
		JsonObject msg = early.remove(id);
		while (msg == null) {
			JsonObject next = readMessage();
			JsonElement nextId = next.get("id");
			if (nextId != null && !nextId.isJsonNull() && nextId.getAsLong() == id) {
				msg = next;
			} else if (nextId != null && !nextId.isJsonNull()) {
				early.put(nextId.getAsLong(), next);
			} else if (next.has("method")) {
				notifications.add(next);
			}
		}
		return msg;
	}

	public JsonObject readMessage() throws IOException {
		String line = out.readLine();
		if (line == null) {
			throw new IOException("engine closed stdout");
		}
		return JsonParser.parseString(line).getAsJsonObject();
	}

	/** Closes stdin and waits for the process to exit. */
	public int closeInput() throws Exception {
		in.close();
		if (!process.waitFor(15, TimeUnit.SECONDS)) {
			throw new AssertionError("engine did not exit after EOF");
		}
		return process.exitValue();
	}

	public int waitForExit() throws InterruptedException {
		if (!process.waitFor(15, TimeUnit.SECONDS)) {
			throw new AssertionError("engine did not exit");
		}
		return process.exitValue();
	}

	public static List<String> ids(JsonObject listResponse) {
		List<String> ids = new ArrayList<>();
		listResponse.getAsJsonArray("result").forEach(e -> ids.add(e.getAsJsonObject().get("id").getAsString()));
		return ids;
	}

	@Override
	public void close() {
		process.destroyForcibly();
	}
}
