package io.github.hexdump0.jreverse.engine.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertPath;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.zip.ZipFile;

import jdk.security.jarsigner.JarSigner;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import io.github.hexdump0.jreverse.engine.Fixtures;

class OverviewTest {

	@TempDir
	static Path tmp;

	private static X509Certificate cert;
	private static PrivateKey key;

	@BeforeAll
	static void keystore() throws Exception {
		Path ks = tmp.resolve("test.p12");
		Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
		Process p = new ProcessBuilder(keytool.toString(), "-genkeypair", "-keystore", ks.toString(), "-storetype", "PKCS12",
				"-storepass", "secret", "-alias", "k", "-keyalg", "RSA", "-keysize", "2048", "-validity", "30",
				"-dname", "CN=JReverse Test, O=JReverse").redirectErrorStream(true).start();
		p.getInputStream().transferTo(new ByteArrayOutputStream());
		assertEquals(0, p.waitFor());
		KeyStore store = KeyStore.getInstance("PKCS12");
		try (InputStream in = Files.newInputStream(ks)) {
			store.load(in, "secret".toCharArray());
		}
		cert = (X509Certificate) store.getCertificate("k");
		key = (PrivateKey) store.getKey("k", "secret".toCharArray());
	}

	@Test
	void v1SignedJar() throws Exception {
		Path signed = tmp.resolve("signed.jar");
		CertPath path = CertificateFactory.getInstance("X.509").generateCertPath(List.of(cert));
		try (ZipFile in = new ZipFile(Fixtures.jar().toFile()); var out = Files.newOutputStream(signed)) {
			new JarSigner.Builder(key, path).build().sign(in, out);
		}
		Signing.Result r;
		try (ZipFile zip = new ZipFile(signed.toFile())) {
			r = Signing.read(signed, zip);
		}
		assertEquals(List.of("v1"), r.schemes());
		assertEquals(1, r.certs().size());
		Signing.Cert c = r.certs().get(0);
		assertTrue(c.subject().contains("CN=JReverse Test"), c.subject());
		assertEquals("RSA 2048", c.key());
		assertEquals(95, c.sha256().length()); // 32 bytes as AA:BB:...
		assertFalse(c.debug());
	}

	@Test
	void v2SigningBlock() throws Exception {
		Path apk = tmp.resolve("v2.apk");
		Files.write(apk, withSigningBlock(Files.readAllBytes(Fixtures.jar()), 0x7109871a, v2Value(cert.getEncoded())));
		Signing.Result r;
		try (ZipFile zip = new ZipFile(apk.toFile())) {
			r = Signing.read(apk, zip);
		}
		assertEquals(List.of("v2"), r.schemes());
		assertTrue(r.certs().get(0).subject().contains("CN=JReverse Test"));
	}

	@Test
	void unsignedHasNoSchemes() throws Exception {
		try (ZipFile zip = new ZipFile(Fixtures.jar().toFile())) {
			assertTrue(Signing.read(Fixtures.jar(), zip).schemes().isEmpty());
		}
	}

	@Test
	void androidManifest() throws Exception {
		String xml = """
				<?xml version="1.0" encoding="utf-8"?>
				<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app"
				    android:versionCode="12" android:versionName="1.2">
				  <uses-sdk android:minSdkVersion="23" android:targetSdkVersion="30"/>
				  <uses-permission android:name="android.permission.CAMERA"/>
				  <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32"/>
				  <permission android:name="com.example.app.PRIVATE" android:protectionLevel="signature"/>
				  <application android:name=".App" android:debuggable="true" android:allowBackup="true">
				    <activity android:name=".MainActivity">
				      <intent-filter>
				        <action android:name="android.intent.action.MAIN"/>
				        <category android:name="android.intent.category.LAUNCHER"/>
				      </intent-filter>
				    </activity>
				    <activity android:name="com.example.app.LinkActivity" android:exported="true">
				      <intent-filter>
				        <action android:name="android.intent.action.VIEW"/>
				        <data android:scheme="https" android:host="example.com" android:pathPrefix="/open"/>
				      </intent-filter>
				    </activity>
				    <service android:name="Sync" android:exported="false"/>
				    <provider android:name=".Files" android:authorities="com.example.files"/>
				  </application>
				</manifest>
				""";
		JsonObject a = Overview.android(xml);
		assertEquals("com.example.app", a.get("package").getAsString());
		assertEquals("1.2", a.get("versionName").getAsString());
		assertEquals("30", a.get("targetSdk").getAsString());
		assertEquals("com.example.app.App", a.get("application").getAsString());
		assertEquals("true", a.get("debuggable").getAsString());
		assertEquals(2, a.getAsJsonArray("permissions").size());
		assertEquals("signature", a.getAsJsonArray("declaredPermissions").get(0).getAsJsonObject().get("protectionLevel")
				.getAsString());

		JsonArray comps = a.getAsJsonArray("components");
		assertEquals(4, comps.size());
		JsonObject main = comps.get(0).getAsJsonObject();
		assertEquals("com.example.app.MainActivity", main.get("name").getAsString());
		assertTrue(main.get("launcher").getAsBoolean());
		// Has an intent filter and targets SDK 30, so it is exported without saying so.
		assertTrue(main.get("exported").getAsBoolean());
		assertTrue(main.get("exportedImplicitly").getAsBoolean());
		JsonObject link = comps.get(1).getAsJsonObject();
		assertEquals("https://example.com/open*", link.getAsJsonArray("links").get(0).getAsString());
		assertFalse(link.get("exportedImplicitly").getAsBoolean());
		JsonObject sync = comps.get(2).getAsJsonObject();
		assertEquals("com.example.app.Sync", sync.get("name").getAsString());
		assertFalse(sync.get("exported").getAsBoolean());
		assertEquals("com.example.files", comps.get(3).getAsJsonObject().get("authorities").getAsString());
	}

	/** A v2 block value with one signer whose signed data holds no digests and one certificate. */
	private static byte[] v2Value(byte[] der) {
		byte[] certs = prefixed(prefixed(der));
		ByteBuffer signedData = ByteBuffer.allocate(4 + certs.length).order(ByteOrder.LITTLE_ENDIAN);
		signedData.putInt(0).put(certs);
		byte[] signer = prefixed(prefixed(signedData.array()));
		return prefixed(signer);
	}

	/** Inserts an APK Signing Block before the central directory and fixes the EOCD's offset. */
	private static byte[] withSigningBlock(byte[] zip, int id, byte[] value) {
		ByteBuffer z = ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN);
		int eocd = zip.length - 22;
		while (z.getInt(eocd) != 0x06054b50) {
			eocd--;
		}
		int cd = z.getInt(eocd + 16);
		long size = 8 + 4 + value.length + 8 + 16;
		ByteBuffer block = ByteBuffer.allocate((int) size + 8).order(ByteOrder.LITTLE_ENDIAN);
		block.putLong(size).putLong(4 + value.length).putInt(id).put(value).putLong(size)
				.put("APK Sig Block 42".getBytes(StandardCharsets.US_ASCII));
		ByteBuffer out = ByteBuffer.allocate(zip.length + block.capacity()).order(ByteOrder.LITTLE_ENDIAN);
		out.put(zip, 0, cd).put(block.array()).put(zip, cd, zip.length - cd);
		out.putInt(eocd + block.capacity() + 16, cd + block.capacity());
		return out.array();
	}

	private static byte[] prefixed(byte[] data) {
		return ByteBuffer.allocate(4 + data.length).order(ByteOrder.LITTLE_ENDIAN).putInt(data.length).put(data).array();
	}
}
