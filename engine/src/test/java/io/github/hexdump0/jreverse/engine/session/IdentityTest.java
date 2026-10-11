package io.github.hexdump0.jreverse.engine.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Base64;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;

import io.github.hexdump0.jreverse.engine.Fixtures;

class IdentityTest {

	private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};

	@TempDir
	Path tmp;

	private static byte[] fixtureClass() throws Exception {
		try (ZipFile zip = new ZipFile(Fixtures.jar().toFile()); InputStream in = zip.getInputStream(zip.getEntry("fixture/Point.class"))) {
			return in.readAllBytes();
		}
	}

	private static JsonObject identity(Path jar) throws Exception {
		Sessions sessions = new Sessions();
		Session s = sessions.open(jar, false);
		try {
			return s.identity();
		} finally {
			sessions.closeAll();
		}
	}

	@Test
	void modNameVersionAndBiggestIcon() throws Exception {
		Path jar = tmp.resolve("mod.jar");
		Fixtures.zip(jar,
				"fabric.mod.json", """
						{"id": "demo", "name": "Demo Mod", "version": "1.2",
						 "icon": {"16": "assets/demo/small.png", "128": "assets/demo/icon.png"}}
						""".getBytes(),
				"assets/demo/icon.png", PNG,
				"assets/demo/small.png", new byte[] {1},
				"fixture/Point.class", fixtureClass());
		JsonObject id = identity(jar);
		assertEquals("Demo Mod", id.get("name").getAsString());
		assertEquals("demo", id.get("id").getAsString());
		assertEquals("1.2", id.get("version").getAsString());
		JsonObject icon = id.getAsJsonObject("icon");
		assertEquals("image/png", icon.get("mime").getAsString());
		assertEquals(Base64.getEncoder().encodeToString(PNG), icon.get("data").getAsString());
		assertFalse(icon.has("adaptive"));
	}

	@Test
	void forgeLogoFile() throws Exception {
		Path jar = tmp.resolve("forge.jar");
		Fixtures.zip(jar,
				"META-INF/mods.toml", """
						logoFile = "logo.png"
						[[mods]]
						modId = "demo"
						version = "3.0"
						displayName = "Demo Forge"
						""".getBytes(),
				"logo.png", PNG,
				"fixture/Point.class", fixtureClass());
		JsonObject id = identity(jar);
		assertEquals("Demo Forge", id.get("name").getAsString());
		assertEquals("image/png", id.getAsJsonObject("icon").get("mime").getAsString());
	}

	@Test
	void libraryTitleFromManifest() throws Exception {
		Path jar = tmp.resolve("lib.jar");
		Fixtures.zip(jar,
				"META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nImplementation-Title: Demo Lib\nImplementation-Version: 4.5\n\n".getBytes(),
				"fixture/Point.class", fixtureClass());
		JsonObject id = identity(jar);
		assertEquals("Demo Lib", id.get("name").getAsString());
		assertEquals("4.5", id.get("version").getAsString());
		assertNull(id.get("icon"));
	}

	@Test
	void plainJarHasNoIdentity() throws Exception {
		assertEquals(new JsonObject(), identity(Fixtures.jar()));
	}

	@Test
	void densities() {
		assertEquals(640, Identity.density("-xxxhdpi-v4"));
		assertEquals(480, Identity.density("-xxhdpi"));
		assertEquals(240, Identity.density("-hdpi-v4"));
		assertEquals(160, Identity.density(""));
		assertEquals(160, Identity.density("-anydpi-v26"));
	}
}
