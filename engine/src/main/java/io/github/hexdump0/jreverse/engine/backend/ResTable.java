package io.github.hexdump0.jreverse.engine.backend;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import jadx.core.dex.nodes.RootNode;
import jadx.core.xmlgen.BinaryXMLStrings;
import jadx.core.xmlgen.ResTableBinaryParser;
import jadx.core.xmlgen.entry.RawValue;
import jadx.core.xmlgen.entry.ResourceEntry;

/**
 * An APK's resources.arsc, for looking up single values: the app's label, the
 * files behind its launcher icon. Names are the ones jadx shows in the decoded
 * manifest, so {@code @string/app_name} there finds {@code string/app_name} here.
 */
public final class ResTable {

	private static final int TYPE_REFERENCE = 0x01;
	private static final int TYPE_STRING = 0x03;
	private static final int TYPE_FIRST_COLOR = 0x1c;
	private static final int TYPE_LAST_COLOR = 0x1f;

	/**
	 * One configuration's value. Exactly one of {@code string}, {@code ref} and
	 * {@code color} is set, or none for values this doesn't read.
	 *
	 * @param config qualifiers as jadx writes them: {@code ""} for the default, {@code -xxhdpi-v4}
	 * @param color  ARGB, 8 bits a channel
	 */
	public record Value(String config, String string, Integer ref, Integer color) {
	}

	private final Map<String, List<Value>> byName = new HashMap<>();
	private final Map<Integer, List<Value>> byId = new HashMap<>();

	private ResTable() {
	}

	/** The table in {@code zip}, or null when it has none. */
	public static ResTable read(RootNode root, ZipFile zip) throws IOException {
		ZipEntry e = zip.getEntry("resources.arsc");
		if (e == null) {
			return null;
		}
		ResTableBinaryParser parser = new ResTableBinaryParser(root);
		try (InputStream in = zip.getInputStream(e)) {
			parser.decode(in);
		}
		BinaryXMLStrings strings = parser.getStrings();
		ResTable table = new ResTable();
		for (ResourceEntry r : parser.getResStorage().getResources()) {
			RawValue raw = r.getSimpleValue();
			Value v;
			if (raw == null) {
				v = new Value(r.getConfig(), null, null, null);
			} else if (raw.getDataType() == TYPE_STRING && strings != null) {
				v = new Value(r.getConfig(), strings.get(raw.getData()), null, null);
			} else if (raw.getDataType() == TYPE_REFERENCE) {
				v = new Value(r.getConfig(), null, raw.getData(), null);
			} else if (raw.getDataType() >= TYPE_FIRST_COLOR && raw.getDataType() <= TYPE_LAST_COLOR) {
				// Stored as ARGB8 whichever form (#rgb, #argb, ...) the source used.
				v = new Value(r.getConfig(), null, null, raw.getData());
			} else {
				v = new Value(r.getConfig(), null, null, null);
			}
			table.byName.computeIfAbsent(r.getTypeName() + "/" + r.getKeyName(), k -> new ArrayList<>()).add(v);
			table.byId.computeIfAbsent(r.getId(), k -> new ArrayList<>()).add(v);
		}
		return table;
	}

	/**
	 * Every configuration of a resource reference as the manifest spells it
	 * ({@code @string/app_name}, {@code @mipmap/ic_launcher}, or an id such as
	 * {@code @0x7f0d0001}). Framework ({@code @android:}) references aren't in the table.
	 */
	public List<Value> lookup(String ref) {
		if (ref == null || !ref.startsWith("@") || ref.startsWith("@android:")) {
			return List.of();
		}
		String name = ref.substring(1);
		if (name.startsWith("0x")) {
			try {
				return lookup(Integer.parseUnsignedInt(name.substring(2), 16));
			} catch (NumberFormatException e) {
				return List.of();
			}
		}
		int colon = name.indexOf(':');
		return byName.getOrDefault(colon >= 0 ? name.substring(colon + 1) : name, List.of());
	}

	public List<Value> lookup(int id) {
		return byId.getOrDefault(id, List.of());
	}
}
