package io.github.hexdump0.jreverse.engine.session;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import io.github.hexdump0.jreverse.engine.rpc.ErrorCode;
import io.github.hexdump0.jreverse.engine.rpc.RpcException;

/** Decides what a file is from its content, never its extension. */
public final class InputDetector {

	private static final byte[] ZIP = {'P', 'K', 3, 4};
	private static final byte[] DEX = {'d', 'e', 'x', '\n'};
	private static final byte[] CLASS = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};
	private static final Pattern ROOT_DEX = Pattern.compile("classes\\d*\\.dex");

	private InputDetector() {
	}

	public static InputKind detect(Path path) throws RpcException {
		byte[] magic = new byte[4];
		try (InputStream in = Files.newInputStream(path)) {
			if (in.readNBytes(magic, 0, 4) < 4) {
				throw unsupported("file is too small to be a Java or Android binary");
			}
		} catch (IOException e) {
			throw new RpcException(ErrorCode.OPEN_FAILED, "cannot read " + path + ": " + e.getMessage(), e);
		}
		if (Arrays.equals(magic, DEX)) {
			return InputKind.DEX;
		}
		if (Arrays.equals(magic, CLASS)) {
			return InputKind.CLASS;
		}
		if (Arrays.equals(magic, ZIP)) {
			return detectZip(path);
		}
		throw unsupported("not an APK, AAR, JAR, DEX or class file");
	}

	private static InputKind detectZip(Path path) throws RpcException {
		boolean hasClassesJar = false;
		boolean hasClassFiles = false;
		boolean isBundle = false;
		try (ZipFile zip = new ZipFile(path.toFile())) {
			Enumeration<? extends ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements()) {
				String name = entries.nextElement().getName();
				if (ROOT_DEX.matcher(name).matches()) {
					return InputKind.APK;
				}
				if (name.equals("classes.jar")) {
					hasClassesJar = true;
				} else if (name.endsWith(".class")) {
					hasClassFiles = true;
				} else if (name.startsWith("base/dex/")) {
					isBundle = true;
				}
			}
		} catch (ZipException e) {
			throw unsupported("damaged archive: " + e.getMessage());
		} catch (IOException e) {
			throw new RpcException(ErrorCode.OPEN_FAILED, "cannot read " + path + ": " + e.getMessage(), e);
		}
		if (hasClassesJar) {
			return InputKind.AAR;
		}
		if (hasClassFiles) {
			return InputKind.JAR;
		}
		if (isBundle) {
			throw unsupported("Android App Bundles (.aab) aren't supported yet");
		}
		throw unsupported("archive contains no DEX or class files");
	}

	private static RpcException unsupported(String message) {
		return new RpcException(ErrorCode.UNSUPPORTED_INPUT, message);
	}
}
