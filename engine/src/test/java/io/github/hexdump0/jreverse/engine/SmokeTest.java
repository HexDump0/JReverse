package io.github.hexdump0.jreverse.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;

/**
 * Runs what ships: the jlink runtime and the fat jar. Catches what unit tests
 * can't see, like a module missing from the runtime or unmerged service files.
 */
@Tag("smoke")
@Timeout(120)
class SmokeTest {

	@TempDir
	Path tmp;

	@Test
	void shippedEngineOpensEveryInputKind() throws Exception {
		Path dist = Path.of(System.getProperty("dist.dir"));
		Map<String, Path> inputs = Map.of(
				"jar", Fixtures.jar(),
				"dex", Fixtures.dex(Files.createDirectories(tmp.resolve("dex"))),
				"apk", Fixtures.apk(Files.createDirectories(tmp.resolve("apk"))),
				"aar", Fixtures.aar(tmp));
		try (EngineClient engine = EngineClient.fromDist(dist)) {
			for (Map.Entry<String, Path> input : inputs.entrySet()) {
				JsonObject open = engine.call("open", "path", input.getValue().toString());
				assertTrue(open.has("result"), input.getKey() + ": " + open);
				JsonObject result = open.getAsJsonObject("result");
				assertEquals(input.getKey(), result.get("kind").getAsString());
				String session = result.get("session").getAsString();
				String cls = EngineClient.ids(engine.call("listClasses", "session", session)).get(0);
				JsonObject decompiled = engine.call("decompile", "session", session, "class", cls);
				assertTrue(decompiled.has("result"), input.getKey() + ": " + decompiled);
			}
			assertTrue(engine.call("shutdown").has("result"));
			assertEquals(0, engine.waitForExit());
		}
	}
}
