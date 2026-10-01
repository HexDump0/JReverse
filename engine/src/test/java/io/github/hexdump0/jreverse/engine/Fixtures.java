package io.github.hexdump0.jreverse.engine;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.android.tools.smali.smali.Smali;
import com.android.tools.smali.smali.SmaliOptions;

/** Test inputs, built from sources so no binaries are checked in. */
public final class Fixtures {

	/** Top-level classes in fixture.jar; {@code Outer$Inner} must not be listed. */
	public static final List<String> JAR_CLASSES = List.of(
			"fixture/Color", "fixture/Greeter", "fixture/Marker", "fixture/Outer", "fixture/Point", "fixture/Shape");

	private Fixtures() {
	}

	public static Path jar() {
		return Path.of(System.getProperty("fixture.jar"));
	}

	public static Path dex(Path dir) throws IOException {
		Path out = dir.resolve("classes.dex");
		SmaliOptions options = new SmaliOptions();
		options.outputDexFile = out.toString();
		List<String> sources;
		try (Stream<Path> files = Files.list(Path.of(System.getProperty("fixture.smali")))) {
			sources = files.map(Path::toString).filter(s -> s.endsWith(".smali")).toList();
		}
		if (!Smali.assemble(options, sources)) {
			throw new IOException("smali failed to assemble fixtures");
		}
		return out;
	}

	/**
	 * A resource table is what makes jadx load its manifest attributes (and with
	 * them java.xml), so the APK fixture needs one to cover that path.
	 */
	public static Path apk(Path dir) throws IOException {
		Path apk = dir.resolve("fixture.apk");
		zip(apk, "AndroidManifest.xml", new byte[] {3, 0, 8, 0}, "resources.arsc", emptyResourceTable(),
				"classes.dex", Files.readAllBytes(dex(dir)));
		return apk;
	}

	/** ResTable header with no packages, followed by an empty global string pool. */
	private static byte[] emptyResourceTable() {
		ByteBuffer buf = ByteBuffer.allocate(12 + 28).order(ByteOrder.LITTLE_ENDIAN);
		buf.putShort((short) 0x0002).putShort((short) 12).putInt(40).putInt(0); // RES_TABLE_TYPE
		buf.putShort((short) 0x0001).putShort((short) 28).putInt(28); // RES_STRING_POOL_TYPE
		buf.putInt(0).putInt(0).putInt(0).putInt(0).putInt(0); // counts, flags, offsets
		return buf.array();
	}

	public static Path aar(Path dir) throws IOException {
		Path aar = dir.resolve("fixture.aar");
		zip(aar, "AndroidManifest.xml", "<manifest/>".getBytes(), "classes.jar", Files.readAllBytes(jar()));
		return aar;
	}

	public static void zip(Path out, Object... nameAndBytes) throws IOException {
		try (OutputStream os = Files.newOutputStream(out); ZipOutputStream zip = new ZipOutputStream(os)) {
			for (int i = 0; i < nameAndBytes.length; i += 2) {
				zip.putNextEntry(new ZipEntry((String) nameAndBytes[i]));
				zip.write((byte[]) nameAndBytes[i + 1]);
				zip.closeEntry();
			}
		}
	}
}
