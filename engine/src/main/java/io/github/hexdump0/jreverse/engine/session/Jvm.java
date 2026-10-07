package io.github.hexdump0.jreverse.engine.session;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import jadx.api.JavaClass;
import jadx.api.JavaNode;
import jadx.api.plugins.input.data.annotations.EncodedType;
import jadx.api.plugins.input.data.annotations.EncodedValue;
import jadx.api.plugins.input.data.annotations.IAnnotation;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.nodes.ClassNode;

/**
 * What a JVM archive says about itself: the mod or plugin descriptor it ships
 * with, the services it provides, the jars and Maven artifacts bundled inside
 * and a WAR's servlets. Class names are Java names ({@code com.foo.Bar}).
 */
final class Jvm {

	private static final Pattern POM = Pattern.compile("META-INF/maven/[^/]+/[^/]+/pom\\.properties");
	private static final Pattern NESTED_JAR = Pattern.compile("(?:WEB-INF/lib|BOOT-INF/lib|META-INF/jars|META-INF/jarjar|lib)/[^/]+\\.jar");
	private static final Pattern MIXIN_CONFIG = Pattern.compile("[^/]*mixins[^/]*\\.json");
	private static final Pattern TOML_KEY = Pattern.compile("^([\\w-]+)\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^#\\s]+)");
	/** Annotations that make a class an entry point, by the kind the UI names. */
	private static final Map<String, String> ENTRY_ANNOTATIONS = Map.of(
			"Lnet/neoforged/fml/common/Mod;", "neoforge",
			"Lnet/minecraftforge/fml/common/Mod;", "forge",
			"Ljakarta/servlet/annotation/WebServlet;", "servlet",
			"Ljavax/servlet/annotation/WebServlet;", "servlet",
			"Ljakarta/servlet/annotation/WebFilter;", "filter",
			"Ljavax/servlet/annotation/WebFilter;", "filter",
			"Ljakarta/servlet/annotation/WebListener;", "listener",
			"Ljavax/servlet/annotation/WebListener;", "listener",
			"Lorg/springframework/boot/autoconfigure/SpringBootApplication;", "spring-boot");
	private static final Map<String, String> ENTRY_INTERFACES = Map.of(
			"burp.api.montoya.BurpExtension", "burp",
			"burp.IBurpExtender", "burp");
	private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";

	private Jvm() {
	}

	/** Reads the archive's descriptors into {@code o}; only what's present is added. */
	static void archive(ZipFile zip, JsonObject o, String implVersion) throws IOException {
		JsonArray artifacts = new JsonArray();
		JsonArray jars = new JsonArray();
		JsonArray services = new JsonArray();
		List<String> mixinConfigs = new ArrayList<>();
		Enumeration<? extends ZipEntry> entries = zip.entries();
		while (entries.hasMoreElements()) {
			ZipEntry e = entries.nextElement();
			String name = e.getName();
			if (e.isDirectory()) {
				continue;
			}
			if (POM.matcher(name).matches()) {
				Properties p = new Properties();
				try (InputStream in = zip.getInputStream(e)) {
					p.load(in);
				}
				if (p.getProperty("artifactId") != null) {
					JsonObject a = new JsonObject();
					a.addProperty("group", p.getProperty("groupId", ""));
					a.addProperty("artifact", p.getProperty("artifactId"));
					put(a, "version", p.getProperty("version"));
					artifacts.add(a);
				}
			} else if (NESTED_JAR.matcher(name).matches()) {
				JsonObject j = new JsonObject();
				j.addProperty("path", name);
				j.addProperty("size", e.getSize());
				jars.add(j);
			} else if (name.startsWith("META-INF/services/") && name.indexOf('/', 18) < 0) {
				JsonArray providers = new JsonArray();
				for (String line : text(zip, e).split("\\R")) {
					String p = line.replaceFirst("#.*", "").trim();
					if (!p.isEmpty()) {
						providers.add(p);
					}
				}
				JsonObject s = new JsonObject();
				s.addProperty("service", name.substring(18));
				s.add("providers", providers);
				services.add(s);
			} else if (name.indexOf('/') < 0 && MIXIN_CONFIG.matcher(name).matches() && !name.contains("refmap")) {
				mixinConfigs.add(name);
			}
		}
		if (!artifacts.isEmpty()) {
			o.add("artifacts", artifacts);
		}
		if (!jars.isEmpty()) {
			o.add("jars", jars);
		}
		if (!services.isEmpty()) {
			o.add("services", services);
		}

		JsonArray plugins = new JsonArray();
		fabric(zip, plugins);
		quilt(zip, plugins);
		for (String file : new String[] {"META-INF/neoforge.mods.toml", "META-INF/mods.toml"}) {
			forge(zip, file, file.contains("neoforge") ? "neoforge" : "forge", implVersion, plugins);
		}
		for (String file : new String[] {"paper-plugin.yml", "plugin.yml", "bungee.yml"}) {
			yaml(zip, file, plugins);
		}
		velocity(zip, plugins);
		if (!plugins.isEmpty()) {
			o.add("plugins", plugins);
		}

		JsonArray mixins = new JsonArray();
		for (String file : mixinConfigs) {
			JsonObject m = mixinConfig(zip, file);
			if (m != null) {
				mixins.add(m);
			}
		}
		if (!mixins.isEmpty()) {
			o.add("mixinConfigs", mixins);
		}

		ZipEntry web = zip.getEntry("WEB-INF/web.xml");
		if (web != null) {
			try {
				o.add("web", webXml(text(zip, web)));
			} catch (Exception ex) {
				System.err.println("engine: could not parse web.xml: " + ex);
			}
		}
	}

	/**
	 * Facts that need the classes: what each mixin class targets, and entry
	 * points no descriptor names (Forge's {@code @Mod}, Burp extensions,
	 * annotated servlets). Annotations and interfaces are read when jadx loads
	 * a class, so this decompiles nothing.
	 */
	static void code(Iterable<JavaNode> nodes, JsonObject o) {
		JsonObject targets = new JsonObject();
		JsonArray entries = new JsonArray();
		for (JavaNode node : nodes) {
			if (!(node instanceof JavaClass cls)) {
				continue;
			}
			ClassNode c = cls.getClassNode();
			IAnnotation mixin = c.getAnnotation(MIXIN);
			if (mixin != null) {
				JsonArray t = new JsonArray();
				collect(mixin.getValues().get("value"), t);
				collect(mixin.getValues().get("targets"), t);
				if (!t.isEmpty()) {
					targets.add(c.getClassInfo().getRawName(), t);
				}
			}
			for (Map.Entry<String, String> a : ENTRY_ANNOTATIONS.entrySet()) {
				IAnnotation ann = c.getAnnotation(a.getKey());
				if (ann != null) {
					JsonArray detail = new JsonArray();
					collect(ann.getValues().get("value"), detail);
					collect(ann.getValues().get("urlPatterns"), detail);
					entries.add(entry(c, a.getValue(), detail));
				}
			}
			for (ArgType t : c.getInterfaces()) {
				String kind = t.isObject() ? ENTRY_INTERFACES.get(t.getObject()) : null;
				if (kind != null) {
					entries.add(entry(c, kind, new JsonArray()));
					break; // Burp's legacy and Montoya interfaces often come together
				}
			}
		}
		if (!targets.isEmpty()) {
			o.add("mixinTargets", targets);
		}
		if (!entries.isEmpty()) {
			o.add("entryClasses", entries);
		}
	}

	private static JsonObject entry(ClassNode c, String kind, JsonArray detail) {
		JsonObject j = new JsonObject();
		j.addProperty("cls", c.getClassInfo().getRawName());
		j.addProperty("kind", kind);
		j.add("detail", detail);
		return j;
	}

	private static void collect(EncodedValue v, JsonArray out) {
		if (v == null) {
			return;
		}
		if (v.getType() == EncodedType.ENCODED_ARRAY && v.getValue() instanceof List<?> list) {
			for (Object item : list) {
				if (item instanceof EncodedValue ev) {
					collect(ev, out);
				}
			}
		} else if (v.getType() == EncodedType.ENCODED_TYPE && v.getValue() instanceof String desc) {
			out.add(desc.startsWith("L") && desc.endsWith(";") ? desc.substring(1, desc.length() - 1).replace('/', '.') : desc);
		} else if (v.getType() == EncodedType.ENCODED_STRING && v.getValue() instanceof String s) {
			out.add(s.replace('/', '.'));
		}
	}

	private static void fabric(ZipFile zip, JsonArray out) {
		JsonObject j = json(zip, "fabric.mod.json");
		if (j == null) {
			return;
		}
		JsonObject p = new JsonObject();
		p.addProperty("loader", "fabric");
		p.addProperty("file", "fabric.mod.json");
		put(p, "id", str(j, "id"));
		put(p, "name", str(j, "name"));
		put(p, "version", str(j, "version"));
		put(p, "description", str(j, "description"));
		p.add("authors", people(j.get("authors")));
		p.add("entries", entrypoints(j.get("entrypoints")));
		p.add("depends", dependencies(j.get("depends")));
		put(p, "environment", str(j, "environment"));
		out.add(p);
	}

	private static void quilt(ZipFile zip, JsonArray out) {
		JsonObject j = json(zip, "quilt.mod.json");
		if (j == null || !(j.get("quilt_loader") instanceof JsonObject ql)) {
			return;
		}
		JsonObject p = new JsonObject();
		p.addProperty("loader", "quilt");
		p.addProperty("file", "quilt.mod.json");
		put(p, "id", str(ql, "id"));
		put(p, "version", str(ql, "version"));
		if (ql.get("metadata") instanceof JsonObject meta) {
			put(p, "name", str(meta, "name"));
			put(p, "description", str(meta, "description"));
			p.add("authors", people(meta.get("contributors") instanceof JsonObject c ? toArray(c.keySet()) : null));
		}
		p.add("entries", entrypoints(ql.get("entrypoints")));
		out.add(p);
	}

	private static JsonArray entrypoints(JsonElement e) {
		JsonArray entries = new JsonArray();
		if (!(e instanceof JsonObject eps)) {
			return entries;
		}
		for (Map.Entry<String, JsonElement> kind : eps.entrySet()) {
			List<JsonElement> values = new ArrayList<>();
			if (kind.getValue() instanceof JsonArray arr) {
				arr.forEach(values::add);
			} else {
				values.add(kind.getValue());
			}
			for (JsonElement v : values) {
				String value = v.isJsonPrimitive() ? v.getAsString() : v instanceof JsonObject vo ? str(vo, "value") : null;
				if (value == null) {
					continue;
				}
				JsonObject en = new JsonObject();
				en.addProperty("kind", kind.getKey());
				// "com.foo.Mod::init" points at a method or field; the class is what we can open.
				en.addProperty("cls", value.contains("::") ? value.substring(0, value.indexOf("::")) : value);
				if (value.contains("::")) {
					en.addProperty("member", value.substring(value.indexOf("::") + 2));
				}
				entries.add(en);
			}
		}
		return entries;
	}

	private static JsonArray dependencies(JsonElement e) {
		JsonArray out = new JsonArray();
		if (e instanceof JsonObject deps) {
			for (Map.Entry<String, JsonElement> d : deps.entrySet()) {
				JsonObject j = new JsonObject();
				j.addProperty("id", d.getKey());
				JsonElement v = d.getValue();
				List<String> versions = new ArrayList<>();
				if (v instanceof JsonArray arr) {
					arr.forEach(x -> versions.add(x.isJsonPrimitive() ? x.getAsString() : x.toString()));
				}
				j.addProperty("version", v.isJsonPrimitive() ? v.getAsString() : v instanceof JsonArray ? String.join(" or ", versions) : v.toString());
				out.add(j);
			}
		}
		return out;
	}

	private static JsonArray people(JsonElement e) {
		JsonArray out = new JsonArray();
		if (e instanceof JsonArray arr) {
			for (JsonElement p : arr) {
				String name = p.isJsonPrimitive() ? p.getAsString() : p instanceof JsonObject po ? str(po, "name") : null;
				if (name != null) {
					out.add(name);
				}
			}
		}
		return out;
	}

	/**
	 * mods.toml is TOML; only flat {@code key = value} lines inside
	 * {@code [[mods]]}, {@code [[mixins]]} and {@code [[dependencies.*]]} matter here.
	 */
	private static void forge(ZipFile zip, String file, String loader, String implVersion, JsonArray out) throws IOException {
		ZipEntry e = zip.getEntry(file);
		if (e == null) {
			return;
		}
		List<Map<String, String>> mods = new ArrayList<>();
		List<String> mixins = new ArrayList<>();
		Map<String, String> top = new LinkedHashMap<>();
		Map<String, String> current = top;
		String table = "";
		for (String raw : text(zip, e).split("\\R")) {
			String line = raw.trim();
			if (line.startsWith("[")) {
				table = line.replaceAll("[\\[\\]\\s]", "");
				current = new LinkedHashMap<>();
				if (table.equals("mods")) {
					mods.add(current);
				}
				continue;
			}
			Matcher m = TOML_KEY.matcher(line);
			if (!m.find()) {
				continue;
			}
			String v = m.group(2);
			if (v.length() >= 2 && (v.startsWith("\"") || v.startsWith("'"))) {
				v = v.substring(1, v.length() - 1);
			}
			if (table.equals("mixins") && m.group(1).equals("config")) {
				mixins.add(v);
			}
			current.put(m.group(1), v);
		}
		for (Map<String, String> mod : mods) {
			JsonObject p = new JsonObject();
			p.addProperty("loader", loader);
			p.addProperty("file", file);
			put(p, "id", mod.get("modId"));
			put(p, "name", mod.get("displayName"));
			String version = mod.get("version");
			put(p, "version", "${file.jarVersion}".equals(version) ? implVersion : version);
			put(p, "description", mod.get("description"));
			JsonArray authors = new JsonArray();
			if (mod.get("authors") != null) {
				authors.add(mod.get("authors"));
			}
			p.add("authors", authors);
			p.add("entries", new JsonArray());
			put(p, "license", top.get("license"));
			out.add(p);
		}
	}

	/** Bukkit, Paper and BungeeCord descriptors; only top-level scalar keys are read. */
	private static void yaml(ZipFile zip, String file, JsonArray out) throws IOException {
		ZipEntry e = zip.getEntry(file);
		if (e == null) {
			return;
		}
		Map<String, String> keys = new LinkedHashMap<>();
		for (String line : text(zip, e).split("\\R")) {
			if (line.isEmpty() || Character.isWhitespace(line.charAt(0)) || line.startsWith("#")) {
				continue;
			}
			int colon = line.indexOf(':');
			if (colon > 0) {
				String v = line.substring(colon + 1).trim();
				if (v.length() >= 2 && (v.startsWith("\"") || v.startsWith("'"))) {
					v = v.substring(1, v.length() - 1);
				}
				keys.put(line.substring(0, colon).trim(), v);
			}
		}
		if (keys.get("main") == null) {
			return;
		}
		JsonObject p = new JsonObject();
		p.addProperty("loader", file.equals("bungee.yml") ? "bungeecord" : file.equals("paper-plugin.yml") ? "paper" : "bukkit");
		p.addProperty("file", file);
		put(p, "name", keys.get("name"));
		put(p, "version", keys.get("version"));
		put(p, "description", keys.get("description"));
		put(p, "apiVersion", keys.get("api-version"));
		JsonArray authors = new JsonArray();
		String a = keys.get("authors") != null ? keys.get("authors") : keys.get("author");
		if (a != null && !a.isEmpty()) {
			for (String s : a.replaceAll("[\\[\\]]", "").split(",")) {
				if (!s.isBlank()) {
					authors.add(s.trim().replaceAll("^[\"']|[\"']$", ""));
				}
			}
		}
		p.add("authors", authors);
		JsonArray entries = new JsonArray();
		JsonObject main = new JsonObject();
		main.addProperty("kind", "main");
		main.addProperty("cls", keys.get("main"));
		entries.add(main);
		p.add("entries", entries);
		out.add(p);
	}

	private static void velocity(ZipFile zip, JsonArray out) {
		JsonObject j = json(zip, "velocity-plugin.json");
		if (j == null) {
			return;
		}
		JsonObject p = new JsonObject();
		p.addProperty("loader", "velocity");
		p.addProperty("file", "velocity-plugin.json");
		put(p, "id", str(j, "id"));
		put(p, "name", str(j, "name"));
		put(p, "version", str(j, "version"));
		put(p, "description", str(j, "description"));
		p.add("authors", people(j.get("authors")));
		JsonArray entries = new JsonArray();
		if (str(j, "main") != null) {
			JsonObject main = new JsonObject();
			main.addProperty("kind", "main");
			main.addProperty("cls", str(j, "main"));
			entries.add(main);
		}
		p.add("entries", entries);
		out.add(p);
	}

	private static JsonObject mixinConfig(ZipFile zip, String file) {
		JsonObject j = json(zip, file);
		if (j == null || str(j, "package") == null) {
			return null;
		}
		String pkg = str(j, "package");
		JsonArray classes = new JsonArray();
		for (String side : new String[] {"mixins", "client", "server"}) {
			if (j.get(side) instanceof JsonArray arr) {
				for (JsonElement m : arr) {
					if (m.isJsonPrimitive()) {
						JsonObject c = new JsonObject();
						c.addProperty("cls", pkg + "." + m.getAsString());
						c.addProperty("side", side.equals("mixins") ? "both" : side);
						classes.add(c);
					}
				}
			}
		}
		JsonObject m = new JsonObject();
		m.addProperty("file", file);
		m.addProperty("package", pkg);
		m.add("classes", classes);
		return m;
	}

	static JsonObject webXml(String xml) throws Exception {
		DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
		f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
		f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		Document doc = f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
		Element root = doc.getDocumentElement();
		Map<String, List<String>> urls = new LinkedHashMap<>();
		for (String kind : new String[] {"servlet", "filter"}) {
			for (Element m : tags(root, kind + "-mapping")) {
				String name = child(m, kind + "-name");
				for (Element u : tags(m, "url-pattern")) {
					urls.computeIfAbsent(kind + ":" + name, k -> new ArrayList<>()).add(u.getTextContent().trim());
				}
			}
		}
		JsonObject o = new JsonObject();
		for (String kind : new String[] {"servlet", "filter"}) {
			JsonArray list = new JsonArray();
			for (Element s : tags(root, kind)) {
				JsonObject j = new JsonObject();
				String name = child(s, kind + "-name");
				put(j, "name", name);
				put(j, "cls", child(s, kind + "-class"));
				put(j, "jsp", child(s, "jsp-file"));
				j.add("urls", toArray(urls.getOrDefault(kind + ":" + name, List.of())));
				list.add(j);
			}
			o.add(kind + "s", list);
		}
		JsonArray listeners = new JsonArray();
		for (Element l : tags(root, "listener")) {
			String cls = child(l, "listener-class");
			if (cls != null) {
				listeners.add(cls);
			}
		}
		o.add("listeners", listeners);
		return o;
	}

	private static List<Element> tags(Element parent, String local) {
		List<Element> out = new ArrayList<>();
		NodeList nodes = parent.getChildNodes();
		for (int i = 0; i < nodes.getLength(); i++) {
			if (nodes.item(i) instanceof Element e && local.equals(e.getTagName().substring(e.getTagName().indexOf(':') + 1))) {
				out.add(e);
			}
		}
		return out;
	}

	private static String child(Element parent, String local) {
		List<Element> list = tags(parent, local);
		return list.isEmpty() ? null : list.get(0).getTextContent().trim();
	}

	private static JsonObject json(ZipFile zip, String name) {
		ZipEntry e = zip.getEntry(name);
		if (e == null) {
			return null;
		}
		try {
			return JsonParser.parseString(text(zip, e)) instanceof JsonObject o ? o : null;
		} catch (Exception ex) {
			System.err.println("engine: could not parse " + name + ": " + ex);
			return null;
		}
	}

	private static String text(ZipFile zip, ZipEntry e) throws IOException {
		try (InputStream in = zip.getInputStream(e)) {
			// Descriptors are small; cap anything that isn't.
			return new String(in.readNBytes(1 << 20), StandardCharsets.UTF_8);
		}
	}

	private static String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}

	private static JsonArray toArray(Iterable<String> items) {
		JsonArray a = new JsonArray();
		items.forEach(a::add);
		return a;
	}

	private static void put(JsonObject o, String key, String value) {
		if (value != null && !value.isEmpty()) {
			o.addProperty(key, value);
		}
	}
}
