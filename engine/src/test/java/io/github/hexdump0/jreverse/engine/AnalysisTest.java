package io.github.hexdump0.jreverse.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Protocol v2: code links, smali, usages, search, export, overview and renames. */
@Timeout(120)
class AnalysisTest {

	private static final String GREET = "fixture/Greeter.greet(Ljava/lang/String;)Ljava/lang/String;";
	private static final String INNER_VALUE = "fixture/Outer$Inner.value()I";

	@TempDir
	static Path tmp;

	private static EngineClient engine;
	private static String jar;

	@BeforeAll
	static void start() throws Exception {
		engine = EngineClient.fromClasspath();
		jar = result(engine.call("open", "path", Fixtures.jar().toString())).get("session").getAsString();
	}

	@AfterAll
	static void stop() {
		engine.close();
	}

	@Test
	void decompileLinksDeclarationsAndReferences() throws Exception {
		JsonObject out = result(engine.call("decompile", "session", jar, "class", "fixture/Outer"));
		String[] lines = out.get("source").getAsString().split("\n", -1);
		JsonArray nodes = out.getAsJsonArray("nodes");
		List<String> declared = spanTexts(out.getAsJsonArray("decls"), nodes, lines);
		assertTrue(declared.contains("Outer=fixture/Outer"), declared.toString());
		assertTrue(declared.contains("Inner=fixture/Outer$Inner"), declared.toString());
		assertTrue(declared.contains("value=" + INNER_VALUE), declared.toString());
		List<String> linked = spanTexts(out.getAsJsonArray("links"), nodes, lines);
		// run() calls value(): a reference, not only the declaration.
		assertEquals(2, linked.stream().filter(s -> s.equals("value=" + INNER_VALUE)).count(), linked.toString());

		JsonObject value = nodeById(nodes, INNER_VALUE);
		assertEquals("method", value.get("kind").getAsString());
		assertEquals("fixture/Outer", value.get("top").getAsString());
		assertEquals("value(): int", value.get("detail").getAsString());
		assertEquals(0, value.getAsJsonArray("frida").size());

		JsonObject greet = result(engine.call("node", "session", jar, "node", GREET));
		assertEquals("[\"java.lang.String\"]", greet.getAsJsonArray("frida").toString());
		assertEquals("public", greet.get("access").getAsString());
		assertError("NO_NODE", engine.call("node", "session", jar, "node", "fixture/Greeter.nope()V"));
	}

	@Test
	void smaliForClassFilesAndDex() throws Exception {
		String bytecode = result(engine.call("smali", "session", jar, "class", "fixture/Greeter")).get("source").getAsString();
		assertTrue(bytecode.contains(".method public greet(Ljava/lang/String;)Ljava/lang/String;"), bytecode);

		Path dex = Fixtures.dex(Files.createDirectories(tmp.resolve("smali-dex")));
		String s = result(engine.call("open", "path", dex.toString())).get("session").getAsString();
		String smali = result(engine.call("smali", "session", s, "class", "smalifix/Hello")).get("source").getAsString();
		assertTrue(smali.contains("const-string"), smali);
	}

	@Test
	void usagesFindCallSites() throws Exception {
		JsonArray usages = result(engine.call("usages", "session", jar, "node", INNER_VALUE)).getAsJsonArray("usages");
		assertEquals(1, usages.size(), usages.toString());
		JsonObject use = usages.get(0).getAsJsonObject();
		assertEquals("fixture/Outer", use.get("cls").getAsString());
		String text = use.get("text").getAsString();
		assertEquals("value", text.substring(use.get("col").getAsInt(), use.get("col").getAsInt() + use.get("len").getAsInt()));
		assertEquals("fixture/Outer.run()I", use.getAsJsonObject("in").get("id").getAsString());

		JsonArray classUses = result(engine.call("usages", "session", jar, "node", "fixture/Outer$Inner")).getAsJsonArray("usages");
		assertFalse(classUses.isEmpty());
		assertEquals(0, result(engine.call("usages", "session", jar, "node", GREET)).getAsJsonArray("usages").size());
	}

	@Test
	void searchNamesCodeAndStrings() throws Exception {
		JsonArray names = hits(engine.call("search", "session", jar, "query", "greet", "scopes", array("classes", "members")));
		assertTrue(names.asList().stream().anyMatch(h -> h.getAsJsonObject().get("type").getAsString().equals("class")));
		assertTrue(names.asList().stream().anyMatch(h -> h.getAsJsonObject().has("node")
				&& h.getAsJsonObject().getAsJsonObject("node").get("id").getAsString().equals(GREET)));

		// Enum constants are fields too, even after their class was decompiled.
		result(engine.call("decompile", "session", jar, "class", "fixture/Color"));
		JsonArray red = hits(engine.call("search", "session", jar, "query", "RED", "caseSensitive", true, "scopes", array("members")));
		assertEquals("fixture/Color.RED:Lfixture/Color;", red.get(0).getAsJsonObject().getAsJsonObject("node").get("id").getAsString());

		JsonObject code = result(engine.call("search", "session", jar, "query", "!", "scopes", array("strings"),
				"ticket", "t-strings"));
		assertEquals(Fixtures.JAR_CLASSES.size(), code.get("searched").getAsInt());
		JsonArray strings = code.getAsJsonArray("hits");
		assertEquals(1, strings.size(), strings.toString());
		assertEquals("string", strings.get(0).getAsJsonObject().get("type").getAsString());
		assertEquals("fixture/Greeter", strings.get(0).getAsJsonObject().get("cls").getAsString());
		assertTrue(engine.notifications().stream().anyMatch(n -> n.get("method").getAsString().equals("progress")
				&& n.getAsJsonObject("params").get("ticket").getAsString().equals("t-strings")));

		// "return" is code, never inside a literal here.
		assertEquals(0, hits(engine.call("search", "session", jar, "query", "return", "scopes", array("strings"))).size());
		JsonArray regex = hits(engine.call("search", "session", jar, "query", "return \\d+", "regex", true, "scopes",
				array("code")));
		assertEquals(1, regex.size(), regex.toString());
		assertError("BAD_REQUEST", engine.call("search", "session", jar, "query", "(", "regex", true));
		assertError("BAD_REQUEST", engine.call("search", "session", jar, "query", "x", "scopes", array("nope")));
		JsonArray limited = result(engine.call("search", "session", jar, "query", "e", "limit", 2)).getAsJsonArray("hits");
		assertEquals(2, limited.size());
	}

	@Test
	void exportWritesOneFilePerClass() throws Exception {
		Path dir = tmp.resolve("export");
		JsonObject out = result(engine.call("export", "session", jar, "dir", dir.toString(), "ticket", "t-export"));
		assertEquals(Fixtures.JAR_CLASSES.size(), out.get("written").getAsInt());
		assertEquals(0, out.get("failed").getAsInt());
		assertTrue(Files.readString(dir.resolve("fixture/Greeter.java")).contains("public String greet("));
		try (Stream<Path> files = Files.walk(dir)) {
			assertEquals(Fixtures.JAR_CLASSES.size(), files.filter(Files::isRegularFile).count());
		}
		assertFalse(result(engine.call("cancel", "ticket", "t-export")).get("cancelled").getAsBoolean());
	}

	@Test
	void overviewOfAJar() throws Exception {
		JsonObject o = result(engine.call("overview", "session", jar));
		assertEquals("jar", o.get("kind").getAsString());
		assertEquals(Fixtures.JAR_CLASSES.size(), o.get("classes").getAsInt());
		assertTrue(o.get("methods").getAsInt() > 5);
		JsonArray versions = o.getAsJsonArray("javaVersions");
		assertEquals("21", versions.get(0).getAsJsonObject().get("java").getAsString());
		assertEquals(0, o.getAsJsonObject("signing").getAsJsonArray("schemes").size());
		assertFalse(o.has("android"));
	}

	@Test
	void renamesAndCommentsShowInTheSource() throws Exception {
		String s = result(engine.call("open", "path", Fixtures.jar().toString())).get("session").getAsString();
		JsonObject renames = new JsonObject();
		renames.addProperty(GREET, "salute");
		renames.addProperty("fixture/Greeter", "Welcomer");
		renames.addProperty("no/Such", "Ignored");
		JsonObject comments = new JsonObject();
		comments.addProperty(GREET, "says hello");
		JsonObject applied = result(engine.call("setCodeData", "session", s, "renames", renames, "comments", comments));
		assertEquals(3, applied.get("applied").getAsInt());

		String source = result(engine.call("decompile", "session", s, "class", "fixture/Greeter")).get("source").getAsString();
		assertTrue(source.contains("public String salute(String"), source);
		assertTrue(source.contains("class Welcomer"), source);
		assertTrue(source.contains("says hello"), source);
		// Ids stay the original names.
		assertEquals("salute", result(engine.call("node", "session", s, "node", GREET)).get("name").getAsString());

		result(engine.call("setCodeData", "session", s, "renames", new JsonObject()));
		String reverted = result(engine.call("decompile", "session", s, "class", "fixture/Greeter")).get("source").getAsString();
		assertTrue(reverted.contains("public String greet(String"), reverted);
	}

	private static List<String> spanTexts(JsonArray spans, JsonArray nodes, String[] lines) {
		List<String> out = new ArrayList<>();
		for (int i = 0; i < spans.size(); i += 4) {
			int line = spans.get(i).getAsInt();
			int col = spans.get(i + 1).getAsInt();
			int len = spans.get(i + 2).getAsInt();
			String id = nodes.get(spans.get(i + 3).getAsInt()).getAsJsonObject().get("id").getAsString();
			out.add(lines[line].substring(col, col + len) + "=" + id);
		}
		return out;
	}

	private static JsonObject nodeById(JsonArray nodes, String id) {
		for (JsonElement n : nodes) {
			if (n.getAsJsonObject().get("id").getAsString().equals(id)) {
				return n.getAsJsonObject();
			}
		}
		throw new AssertionError("no node " + id + " in " + nodes);
	}

	private static JsonArray array(String... items) {
		JsonArray a = new JsonArray();
		for (String s : items) {
			a.add(s);
		}
		return a;
	}

	private static JsonArray hits(JsonObject response) {
		return result(response).getAsJsonArray("hits");
	}

	private static JsonObject result(JsonObject response) {
		assertTrue(response.has("result"), response.toString());
		return response.getAsJsonObject("result");
	}

	private static void assertError(String code, JsonObject response) {
		assertTrue(response.has("error"), response.toString());
		assertEquals(code, response.getAsJsonObject("error").get("code").getAsString(), response.toString());
	}
}
