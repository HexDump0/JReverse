package io.github.hexdump0.jreverse.engine.backend;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jadx.api.JadxDecompiler;
import jadx.api.ResourceFile;
import jadx.api.ResourceType;
import jadx.api.ResourcesLoader;
import jadx.core.xmlgen.ResContainer;

import io.github.hexdump0.jreverse.engine.rpc.ErrorCode;
import io.github.hexdump0.jreverse.engine.rpc.RpcException;

/**
 * Everything in the input that isn't code: resources, assets, configs. Binary
 * XML is decoded, and resources.arsc turns into the res/values files it holds
 * when someone opens it (decoding the table takes a moment, so not before).
 */
public final class ArchiveFiles {

	/** Text past this is cut, with {@code truncated} set. */
	static final int MAX_TEXT = 4 << 20;
	/** Binary files show this much as hex. */
	static final int MAX_BINARY = 64 << 10;
	/** Images up to this size are sent whole. */
	static final int MAX_IMAGE = 8 << 20;

	public record Entry(String path, String type, long size) {
	}

	/**
	 * @param kind     {@code text}, {@code image}, {@code binary} or {@code table} (resources.arsc: see {@code children})
	 * @param data     base64: the image, or the first bytes of a binary file
	 * @param children for a table, the paths of the files it decodes to
	 */
	public record Content(String path, String kind, long size, String text, String data, String mime, List<String> children,
			boolean truncated) {
	}

	private final JadxDecompiler jadx;
	private final Map<String, ResourceFile> byPath = new LinkedHashMap<>();
	/** Files decoded out of resources.arsc, once it has been opened. */
	private final Map<String, ResContainer> fromTable = new LinkedHashMap<>();

	ArchiveFiles(JadxDecompiler jadx) {
		this.jadx = jadx;
	}

	public synchronized List<Entry> list() {
		load();
		List<Entry> out = new ArrayList<>();
		for (ResourceFile r : byPath.values()) {
			long size = r.getZipEntry() != null ? r.getZipEntry().getUncompressedSize() : -1;
			out.add(new Entry(r.getOriginalName(), typeName(r.getType()), size));
		}
		for (String path : fromTable.keySet()) {
			out.add(new Entry(path, "xml", -1));
		}
		return out;
	}

	public synchronized Content read(String path) throws RpcException {
		load();
		ResContainer decoded = fromTable.get(path);
		if (decoded != null) {
			return text(path, decoded.getText().getCodeStr(), -1);
		}
		ResourceFile r = byPath.get(path);
		if (r == null) {
			throw new RpcException(ErrorCode.NO_FILE, "no such file: " + path);
		}
		long size = r.getZipEntry() != null ? r.getZipEntry().getUncompressedSize() : -1;
		try {
			switch (r.getType()) {
				case ARSC -> {
					ResContainer table = r.loadContent();
					List<String> children = new ArrayList<>();
					collect(table, children);
					return new Content(path, "table", size, null, null, null, children, false);
				}
				case MANIFEST, XML -> {
					byte[] raw = bytes(r);
					// Plain-text XML (in a JAR, say) is shown as is; binary XML is decoded.
					if (raw.length > 0 && raw[0] == '<') {
						return text(path, new String(raw, StandardCharsets.UTF_8), size);
					}
					ResContainer c = r.loadContent();
					if (c.getDataType() == ResContainer.DataType.TEXT) {
						return text(path, c.getText().getCodeStr(), size);
					}
					return raw(path, raw, size);
				}
				case IMG -> {
					byte[] raw = bytes(r);
					String mime = imageMime(path);
					if (mime != null && raw.length <= MAX_IMAGE) {
						return new Content(path, "image", size, null, Base64.getEncoder().encodeToString(raw), mime, List.of(), false);
					}
					return raw(path, raw, size);
				}
				default -> {
					return raw(path, bytes(r), size);
				}
			}
		} catch (RpcException e) {
			throw e;
		} catch (Exception | StackOverflowError e) {
			throw new RpcException(ErrorCode.DECODE_FAILED, "could not read " + path + ": " + e, e);
		}
	}

	private void load() {
		if (!byPath.isEmpty()) {
			return;
		}
		for (ResourceFile r : jadx.getResources()) {
			if (r.getType() != ResourceType.CODE) {
				byPath.putIfAbsent(r.getOriginalName(), r);
			}
		}
	}

	private void collect(ResContainer c, List<String> into) {
		for (ResContainer sub : c.getSubFiles()) {
			if (sub.getDataType() == ResContainer.DataType.TEXT) {
				fromTable.putIfAbsent(sub.getName(), sub);
				into.add(sub.getName());
			}
			collect(sub, into);
		}
	}

	private static byte[] bytes(ResourceFile r) throws Exception {
		return ResourcesLoader.decodeStream(r, (size, in) -> in.readAllBytes());
	}

	/** Text if it decodes as UTF-8 without NULs, otherwise the first bytes for a hex view. */
	private static Content raw(String path, byte[] raw, long size) {
		int probe = Math.min(raw.length, 8192);
		boolean nul = false;
		for (int i = 0; i < probe && !nul; i++) {
			nul = raw[i] == 0;
		}
		if (!nul) {
			try {
				String s = StandardCharsets.UTF_8.newDecoder()
						.onMalformedInput(CodingErrorAction.REPORT)
						.decode(ByteBuffer.wrap(raw, 0, Math.min(raw.length, MAX_TEXT))).toString();
				return text(path, s, size);
			} catch (CharacterCodingException e) {
				// Binary after all.
			}
		}
		byte[] head = Arrays.copyOf(raw, Math.min(raw.length, MAX_BINARY));
		return new Content(path, "binary", size >= 0 ? size : raw.length, null, Base64.getEncoder().encodeToString(head), null,
				List.of(), raw.length > head.length);
	}

	private static Content text(String path, String s, long size) {
		boolean cut = s.length() > MAX_TEXT;
		return new Content(path, "text", size, cut ? s.substring(0, MAX_TEXT) : s, null, null, List.of(), cut);
	}

	private static String imageMime(String path) {
		String p = path.toLowerCase(Locale.ROOT);
		if (p.endsWith(".png")) {
			return "image/png";
		}
		if (p.endsWith(".jpg") || p.endsWith(".jpeg")) {
			return "image/jpeg";
		}
		if (p.endsWith(".gif")) {
			return "image/gif";
		}
		if (p.endsWith(".webp")) {
			return "image/webp";
		}
		if (p.endsWith(".bmp")) {
			return "image/bmp";
		}
		return null;
	}

	private static String typeName(ResourceType t) {
		return switch (t) {
			case MANIFEST -> "manifest";
			case ARSC -> "arsc";
			case XML -> "xml";
			case IMG -> "image";
			case LIB -> "lib";
			case FONT -> "font";
			case ARCHIVE, APK -> "archive";
			case JSON, TEXT, HTML -> "text";
			default -> "file";
		};
	}
}
