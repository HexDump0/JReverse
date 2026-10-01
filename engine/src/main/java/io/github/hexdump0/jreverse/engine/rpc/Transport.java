package io.github.hexdump0.jreverse.engine.rpc;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/** Writes protocol messages, one compact JSON object per line. Thread-safe. */
public final class Transport {

	static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

	private final Writer out;

	public Transport(OutputStream out) {
		this.out = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
	}

	public void result(JsonElement id, JsonElement result) {
		JsonObject msg = new JsonObject();
		msg.add("id", id);
		msg.add("result", result != null ? result : new JsonObject());
		send(msg);
	}

	public void error(JsonElement id, ErrorCode code, String message) {
		JsonObject err = new JsonObject();
		err.addProperty("code", code.name());
		err.addProperty("message", message);
		JsonObject msg = new JsonObject();
		msg.add("id", id != null ? id : JsonNull.INSTANCE);
		msg.add("error", err);
		send(msg);
	}

	public void notify(String method, JsonElement params) {
		JsonObject msg = new JsonObject();
		msg.addProperty("method", method);
		msg.add("params", params);
		send(msg);
	}

	private synchronized void send(JsonObject msg) {
		try {
			// Gson escapes control characters, so the line can't contain a raw newline.
			out.write(GSON.toJson(msg));
			out.write('\n');
			out.flush();
		} catch (IOException e) {
			// The client is gone; the read loop will see EOF and shut down.
			System.err.println("engine: failed to write message: " + e);
		}
	}
}
