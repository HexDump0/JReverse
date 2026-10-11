package io.github.hexdump0.jreverse.engine.session;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.github.hexdump0.jreverse.engine.backend.ArchiveFiles;
import io.github.hexdump0.jreverse.engine.backend.ResTable;

/**
 * What a file calls itself, for lists of files: an app's label, package,
 * version and launcher icon; a mod's or plugin's name, id, version and icon;
 * a library's title and version. Built from the overview, plus the resource
 * table for an APK's label and icon.
 */
final class Identity {

	private static final String ANDROID = "http://schemas.android.com/apk/res/android";
	private static final int MAX_ICON = 1 << 20;
	private static final Pattern DENSITY = Pattern.compile("-(l|m|tv|h|xh|xxh|xxxh)dpi(?:-|$)");
	/** References can point at references; real tables go one or two deep. */
	private static final int MAX_DEPTH = 4;

	/** An icon image, plus for an adaptive icon the layer under it. */
	record Icon(String mime, byte[] data, boolean adaptive, Integer background, Icon backdrop) {
	}

	private Identity() {
	}

	static JsonObject build(Session session) {
		JsonObject overview = session.overview();
		JsonObject o = new JsonObject();
		if (session.kind() == InputKind.DEX || session.kind() == InputKind.CLASS) {
			return o;
		}
		try (ZipFile zip = new ZipFile(session.path().toFile())) {
			if (overview.get("android") instanceof JsonObject android) {
				android(session, zip, android, o);
			}
			if (!o.has("name")) {
				jvm(zip, overview, o);
			}
		} catch (IOException | RuntimeException e) {
			System.err.println("engine: could not read the identity of " + session.path() + ": " + e);
		}
		return o;
	}

	private static void android(Session session, ZipFile zip, JsonObject android, JsonObject o) throws IOException {
		put(o, "id", str(android, "package"));
		put(o, "version", str(android, "versionName"));
		String label = str(android, "label");
		String icon = str(android, "icon");
		ResTable table = null;
		if ((label != null && label.startsWith("@")) || icon != null) {
			table = ResTable.read(session.jadx().root(), zip);
		}
		if (label != null && !label.startsWith("@")) {
			put(o, "name", label);
		} else if (table != null) {
			put(o, "name", string(table, table.lookup(label), 0));
		}
		if (table != null && icon != null) {
			Icon found = icon(table, zip, session.jadx().files(), table.lookup(icon), 0, true);
			if (found != null) {
				o.add("icon", json(found));
			}
		}
	}

	/** The first descriptor's name, else the jar manifest's title, else a lone Maven artifact. */
	private static void jvm(ZipFile zip, JsonObject overview, JsonObject o) throws IOException {
		if (overview.get("plugins") instanceof JsonArray plugins && !plugins.isEmpty()) {
			JsonObject p = plugins.get(0).getAsJsonObject();
			put(o, "name", str(p, "name") != null ? str(p, "name") : str(p, "id"));
			put(o, "id", str(p, "id"));
			put(o, "version", str(p, "version"));
			String path = str(p, "icon");
			ZipEntry e = path != null ? zip.getEntry(path) : null;
			String mime = path != null ? mime(path) : null;
			if (e != null && mime != null && e.getSize() <= MAX_ICON) {
				try (InputStream in = zip.getInputStream(e)) {
					o.add("icon", json(new Icon(mime, in.readNBytes(MAX_ICON), false, null, null)));
				}
			}
			return;
		}
		if (overview.get("jarManifest") instanceof JsonObject mf && str(mf, "Implementation-Title") != null) {
			put(o, "name", str(mf, "Implementation-Title"));
			put(o, "version", str(mf, "Implementation-Version"));
			return;
		}
		if (overview.get("artifacts") instanceof JsonArray artifacts && artifacts.size() == 1) {
			JsonObject a = artifacts.get(0).getAsJsonObject();
			put(o, "name", str(a, "artifact"));
			put(o, "id", str(a, "group") + ":" + str(a, "artifact"));
			put(o, "version", str(a, "version"));
		}
	}

	/** The default configuration's string, else the first one; references are followed. */
	private static String string(ResTable table, List<ResTable.Value> values, int depth) {
		if (depth > MAX_DEPTH || values.isEmpty()) {
			return null;
		}
		ResTable.Value v = values.stream().filter(x -> x.config().isEmpty()).findFirst().orElse(values.get(0));
		if (v.ref() != null) {
			return string(table, table.lookup(v.ref()), depth + 1);
		}
		return v.string() == null || v.string().isBlank() ? null : v.string().trim();
	}

	/**
	 * The sharpest bitmap among a drawable's configurations. Failing that, an
	 * adaptive icon (Android 8+) whose foreground is a bitmap, with its
	 * background colour or bitmap. Vector drawables aren't drawn.
	 */
	private static Icon icon(ResTable table, ZipFile zip, ArchiveFiles files, List<ResTable.Value> values, int depth,
			boolean adaptive) {
		if (depth > MAX_DEPTH) {
			return null;
		}
		List<ResTable.Value> bitmaps = values.stream()
				.filter(v -> v.string() != null && mime(v.string()) != null && !v.string().endsWith(".9.png"))
				.sorted(Comparator.comparingInt((ResTable.Value v) -> density(v.config())).reversed())
				.toList();
		for (ResTable.Value v : bitmaps) {
			byte[] data = bytes(zip, v.string());
			if (data != null) {
				return new Icon(mime(v.string()), data, false, null, null);
			}
		}
		for (ResTable.Value v : values) {
			if (v.ref() != null) {
				Icon i = icon(table, zip, files, table.lookup(v.ref()), depth + 1, adaptive);
				if (i != null) {
					return i;
				}
			}
		}
		if (!adaptive) {
			return null;
		}
		for (ResTable.Value v : values) {
			if (v.string() == null || !v.string().endsWith(".xml")) {
				continue;
			}
			Element root = xml(files, v.string());
			if (root == null || !root.getLocalName().equals("adaptive-icon")) {
				continue;
			}
			Icon fg = layer(table, zip, files, root, "foreground", depth);
			if (fg == null) {
				continue;
			}
			String bgRef = drawable(root, "background");
			Integer color = bgRef != null ? color(table, table.lookup(bgRef), depth + 1) : null;
			Icon bg = bgRef != null && color == null ? layer(table, zip, files, root, "background", depth) : null;
			return new Icon(fg.mime(), fg.data(), true, color, bg);
		}
		return null;
	}

	private static Icon layer(ResTable table, ZipFile zip, ArchiveFiles files, Element root, String tag, int depth) {
		String ref = drawable(root, tag);
		return ref == null ? null : icon(table, zip, files, table.lookup(ref), depth + 1, false);
	}

	private static Integer color(ResTable table, List<ResTable.Value> values, int depth) {
		if (depth > MAX_DEPTH) {
			return null;
		}
		for (ResTable.Value v : values) {
			if (v.color() != null) {
				return v.color();
			}
			if (v.ref() != null) {
				Integer c = color(table, table.lookup(v.ref()), depth + 1);
				if (c != null) {
					return c;
				}
			}
		}
		return null;
	}

	/** {@code android:drawable} of an adaptive icon's {@code <foreground>} or {@code <background>}. */
	private static String drawable(Element root, String tag) {
		for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
			if (n instanceof Element e && tag.equals(e.getLocalName())) {
				String d = e.getAttributeNS(ANDROID, "drawable");
				return d.isEmpty() ? null : d;
			}
		}
		return null;
	}

	private static Element xml(ArchiveFiles files, String path) {
		try {
			ArchiveFiles.Content c = files.read(path);
			if (c.text() == null) {
				return null;
			}
			DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
			f.setNamespaceAware(true);
			f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			return f.newDocumentBuilder().parse(new InputSource(new StringReader(c.text()))).getDocumentElement();
		} catch (Exception e) {
			return null;
		}
	}

	/** Dots per inch a configuration is drawn for; the default counts as mdpi. */
	static int density(String config) {
		Matcher m = DENSITY.matcher(config);
		if (!m.find()) {
			return 160;
		}
		return switch (m.group(1)) {
			case "l" -> 120;
			case "m" -> 160;
			case "tv" -> 213;
			case "h" -> 240;
			case "xh" -> 320;
			case "xxh" -> 480;
			default -> 640;
		};
	}

	private static byte[] bytes(ZipFile zip, String path) {
		ZipEntry e = zip.getEntry(path);
		if (e == null || e.getSize() > MAX_ICON) {
			return null;
		}
		try (InputStream in = zip.getInputStream(e)) {
			return in.readNBytes(MAX_ICON);
		} catch (IOException ex) {
			return null;
		}
	}

	static String mime(String path) {
		String p = path.toLowerCase(Locale.ROOT);
		if (p.endsWith(".png")) {
			return "image/png";
		}
		if (p.endsWith(".webp")) {
			return "image/webp";
		}
		if (p.endsWith(".jpg") || p.endsWith(".jpeg")) {
			return "image/jpeg";
		}
		return null;
	}

	private static JsonObject json(Icon icon) {
		JsonObject o = new JsonObject();
		o.addProperty("mime", icon.mime());
		o.addProperty("data", Base64.getEncoder().encodeToString(icon.data()));
		if (icon.adaptive()) {
			o.addProperty("adaptive", true);
		}
		if (icon.background() != null) {
			int argb = icon.background();
			o.addProperty("background", String.format("#%08x", (argb << 8) | (argb >>> 24))); // CSS wants #rrggbbaa
		}
		if (icon.backdrop() != null) {
			o.add("backdrop", json(icon.backdrop()));
		}
		return o;
	}

	private static String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}

	private static void put(JsonObject o, String key, String value) {
		if (value != null && !value.isBlank()) {
			o.addProperty(key, value);
		}
	}
}
