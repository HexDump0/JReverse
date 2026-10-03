package io.github.hexdump0.jreverse.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;

@Timeout(120)
class ProtocolTest {

	@TempDir
	static Path tmp;

	private static EngineClient engine;

	@BeforeAll
	static void start() throws Exception {
		engine = EngineClient.fromClasspath();
	}

	@AfterAll
	static void stop() {
		engine.close();
	}

	@Test
	void handshake() {
		JsonObject ready = engine.ready();
		assertEquals("ready", ready.get("method").getAsString());
		JsonObject params = ready.getAsJsonObject("params");
		assertEquals(Version.PROTOCOL, params.get("protocol").getAsInt());
		assertEquals("jadx", params.getAsJsonArray("engines").get(0).getAsString());
	}

	@Test
	void openListAndDecompileJar() throws Exception {
		JsonObject open = result(engine.call("open", "path", Fixtures.jar().toString()));
		assertEquals("jar", open.get("kind").getAsString());
		assertEquals(Fixtures.JAR_CLASSES.size(), open.get("classCount").getAsInt());
		String session = open.get("session").getAsString();

		JsonObject list = engine.call("listClasses", "session", session);
		assertEquals(Fixtures.JAR_CLASSES, EngineClient.ids(list));
		Map<String, String> kinds = list.getAsJsonArray("result").asList().stream()
				.map(e -> e.getAsJsonObject())
				.collect(Collectors.toMap(o -> o.get("id").getAsString(), o -> o.get("kind").getAsString()));
		assertEquals(Map.of(
				"fixture/Color", "enum", "fixture/Greeter", "class", "fixture/Marker", "annotation",
				"fixture/Outer", "class", "fixture/Point", "record", "fixture/Shape", "interface"), kinds);

		JsonObject greeter = result(engine.call("decompile", "session", session, "class", "fixture/Greeter"));
		assertEquals("jadx", greeter.get("engine").getAsString());
		assertEquals(0, greeter.get("warnings").getAsInt());
		String source = greeter.get("source").getAsString();
		assertTrue(source.contains("public String greet(String"), source);

		String outer = result(engine.call("decompile", "session", session, "class", "fixture/Outer"))
				.get("source").getAsString();
		assertTrue(outer.contains("class Inner"), "inner classes are part of the outer class's source");

		assertEquals(Map.of(), result(engine.call("close", "session", session)).asMap());
		assertError("NO_SESSION", engine.call("listClasses", "session", session));
	}

	@Test
	void openDexApkAabAndAar() throws Exception {
		Map<String, Path> inputs = Map.of(
				"dex", Fixtures.dex(Files.createDirectories(tmp.resolve("dex"))),
				"apk", Fixtures.apk(Files.createDirectories(tmp.resolve("apk"))),
				"aab", Fixtures.aab(Files.createDirectories(tmp.resolve("aab"))),
				"aar", Fixtures.aar(tmp));
		for (Map.Entry<String, Path> input : inputs.entrySet()) {
			JsonObject open = result(engine.call("open", "path", input.getValue().toString()));
			assertEquals(input.getKey(), open.get("kind").getAsString());
			String session = open.get("session").getAsString();
			List<String> ids = EngineClient.ids(engine.call("listClasses", "session", session));
			String cls = input.getKey().equals("aar") ? "fixture/Greeter" : "smalifix/Hello";
			assertTrue(ids.contains(cls), ids.toString());
			String source = result(engine.call("decompile", "session", session, "class", cls)).get("source").getAsString();
			assertTrue(source.contains(input.getKey().equals("aar") ? "greet(String" : "\"Hello, \""), source);
		}
	}

	@Test
	void concurrentRequestsAreAllAnswered() throws Exception {
		String session = result(engine.call("open", "path", Fixtures.jar().toString())).get("session").getAsString();
		List<Long> ids = Fixtures.JAR_CLASSES.stream().map(cls -> {
			try {
				return engine.send("decompile", "session", session, "class", cls);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}).toList();
		for (long id : ids) {
			assertFalse(result(engine.await(id)).get("source").getAsString().isEmpty());
		}
	}

	@Test
	void errors() throws Exception {
		String session = result(engine.call("open", "path", Fixtures.jar().toString())).get("session").getAsString();
		assertError("NO_CLASS", engine.call("decompile", "session", session, "class", "no/Such"));
		assertError("NO_ENGINE", engine.call("decompile", "session", session, "class", "fixture/Greeter", "engine", "cfr"));
		assertError("NO_SESSION", engine.call("decompile", "session", "s999", "class", "fixture/Greeter"));
		assertError("UNKNOWN_METHOD", engine.call("frobnicate"));
		assertError("BAD_REQUEST", engine.call("open"));
		assertError("OPEN_FAILED", engine.call("open", "path", tmp.resolve("missing.apk").toString()));
		Path text = tmp.resolve("notes.txt");
		Files.writeString(text, "not a binary");
		assertError("UNSUPPORTED_INPUT", engine.call("open", "path", text.toString()));

		engine.sendRaw("{not json");
		assertBadRequestWithoutId(engine.readMessage());
		engine.sendRaw("[1,2]");
		assertBadRequestWithoutId(engine.readMessage());
		engine.sendRaw("{\"id\":77,\"method\":\"open\",\"params\":[1]}");
		assertError("BAD_REQUEST", engine.await(77));

		// Still healthy afterwards.
		assertTrue(engine.call("listClasses", "session", session).has("result"));
	}

	@Test
	void shutdownAnswersThenExits() throws Exception {
		try (EngineClient own = EngineClient.fromClasspath()) {
			assertTrue(own.call("shutdown").has("result"));
			assertEquals(0, own.waitForExit());
		}
	}

	@Test
	void exitsOnEof() throws Exception {
		try (EngineClient own = EngineClient.fromClasspath()) {
			own.call("open", "path", Fixtures.jar().toString());
			assertEquals(0, own.closeInput());
		}
	}

	private static JsonObject result(JsonObject response) {
		assertTrue(response.has("result"), response.toString());
		return response.getAsJsonObject("result");
	}

	private static void assertError(String code, JsonObject response) {
		assertTrue(response.has("error"), response.toString());
		assertEquals(code, response.getAsJsonObject("error").get("code").getAsString(), response.toString());
	}

	private static void assertBadRequestWithoutId(JsonObject response) {
		assertTrue(response.get("id").isJsonNull(), response.toString());
		assertError("BAD_REQUEST", response);
	}
}
