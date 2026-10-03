package io.github.hexdump0.jreverse.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.hexdump0.jreverse.engine.rpc.ErrorCode;
import io.github.hexdump0.jreverse.engine.rpc.RpcException;
import io.github.hexdump0.jreverse.engine.session.InputDetector;
import io.github.hexdump0.jreverse.engine.session.InputKind;

class InputDetectorTest {

	@TempDir
	Path tmp;

	@Test
	void detectsEveryKindByContent() throws Exception {
		assertEquals(InputKind.JAR, InputDetector.detect(Fixtures.jar()));
		assertEquals(InputKind.DEX, InputDetector.detect(Fixtures.dex(tmp)));
		assertEquals(InputKind.APK, InputDetector.detect(Fixtures.apk(tmp)));
		assertEquals(InputKind.AAR, InputDetector.detect(Fixtures.aar(tmp)));
		assertEquals(InputKind.AAB, InputDetector.detect(Fixtures.aab(Files.createDirectories(tmp.resolve("aab")))));

		Path cls = tmp.resolve("Thing.bin");
		Files.write(cls, new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 65});
		assertEquals(InputKind.CLASS, InputDetector.detect(cls));
	}

	@Test
	void extensionIsIgnored() throws Exception {
		Path renamed = tmp.resolve("not-a-jar.apk");
		Files.copy(Fixtures.jar(), renamed);
		assertEquals(InputKind.JAR, InputDetector.detect(renamed));
	}

	@Test
	void rejectsUnsupportedInput() throws Exception {
		Path text = tmp.resolve("notes.txt");
		Files.writeString(text, "hello world");
		assertUnsupported(text);

		Path tiny = tmp.resolve("tiny");
		Files.write(tiny, new byte[] {1, 2});
		assertUnsupported(tiny);

		Path emptyZip = tmp.resolve("empty.zip");
		Fixtures.zip(emptyZip, "readme.txt", "hi".getBytes());
		assertUnsupported(emptyZip);

		Path broken = tmp.resolve("broken.jar");
		Files.write(broken, new byte[] {'P', 'K', 3, 4, 9, 9, 9});
		assertUnsupported(broken);
	}

	private static void assertUnsupported(Path path) {
		RpcException e = assertThrows(RpcException.class, () -> InputDetector.detect(path));
		assertEquals(ErrorCode.UNSUPPORTED_INPUT, e.code(), e.getMessage());
	}
}
