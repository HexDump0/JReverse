package io.github.hexdump0.jreverse.engine.session;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.api.JavaNode;

import io.github.hexdump0.jreverse.engine.backend.JadxBackend;

/**
 * The facts a first look at a file needs: what's inside the archive, who
 * signed it, and for Android the manifest's identity, permissions and entry
 * points. Built once per session on request.
 */
public final class Overview {

	private static final String ANDROID = "http://schemas.android.com/apk/res/android";
	// App Bundles put both under a module folder: base/lib/..., base/dex/...
	private static final Pattern NATIVE_LIB = Pattern.compile("(?:[^/]+/)?(?:lib|jni)/([^/]+)/([^/]+\\.so)");
	private static final Pattern DEX = Pattern.compile("(?:[^/]+/dex/)?classes\\d*\\.dex");
	private static final String[] COMPONENTS = {"activity", "activity-alias", "service", "receiver", "provider"};

	private Overview() {
	}

	public static JsonObject build(Session session) {
		JsonObject o = new JsonObject();
		Path path = session.path();
		JadxBackend jadx = session.jadx();
		o.addProperty("path", path.toString());
		o.addProperty("kind", session.kind().wireName());
		try {
			o.addProperty("size", Files.size(path));
		} catch (IOException e) {
			o.addProperty("size", 0);
		}
		o.addProperty("classes", jadx.classes().size());
		int methods = 0;
		int fields = 0;
		for (JavaNode node : jadx.allNodes()) {
			if (node instanceof JavaMethod) {
				methods++;
			} else if (node instanceof JavaField) {
				fields++;
			}
		}
		o.addProperty("methods", methods);
		o.addProperty("fields", fields);

		if (session.kind() == InputKind.CLASS) {
			o.add("javaVersions", javaVersions(Map.of(classVersion(path), 1)));
		} else if (session.kind() != InputKind.DEX) {
			zip(o, path);
		}

		String manifest = jadx.manifest();
		if (manifest != null && session.kind() != InputKind.JAR) {
			o.addProperty("manifest", manifest);
			try {
				o.add("android", android(manifest));
			} catch (Exception e) {
				System.err.println("engine: could not parse the manifest: " + e);
			}
		}
		return o;
	}

	private static void zip(JsonObject o, Path path) {
		JsonArray dex = new JsonArray();
		JsonArray libs = new JsonArray();
		Map<Integer, Integer> versions = new TreeMap<>();
		int files = 0;
		try (ZipFile zip = new ZipFile(path.toFile())) {
			Enumeration<? extends ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry e = entries.nextElement();
				if (e.isDirectory()) {
					continue;
				}
				files++;
				String name = e.getName();
				var lib = NATIVE_LIB.matcher(name);
				if (DEX.matcher(name).matches()) {
					JsonObject d = new JsonObject();
					d.addProperty("name", name);
					d.addProperty("size", e.getSize());
					dex.add(d);
				} else if (lib.matches()) {
					JsonObject l = new JsonObject();
					l.addProperty("abi", lib.group(1));
					l.addProperty("name", lib.group(2));
					l.addProperty("size", e.getSize());
					libs.add(l);
				} else if (name.endsWith(".class") && !name.startsWith("META-INF/versions/")) {
					try (InputStream in = zip.getInputStream(e)) {
						byte[] head = in.readNBytes(8);
						if (head.length == 8 && (head[0] & 0xff) == 0xca) {
							versions.merge(((head[6] & 0xff) << 8) | (head[7] & 0xff), 1, Integer::sum);
						}
					}
				}
			}
			o.addProperty("files", files);
			o.add("dex", dex);
			o.add("nativeLibs", libs);
			if (!versions.isEmpty()) {
				o.add("javaVersions", javaVersions(versions));
			}
			ZipEntry mf = zip.getEntry("META-INF/MANIFEST.MF");
			if (mf != null) {
				try (InputStream in = zip.getInputStream(mf)) {
					JsonObject attrs = jarManifest(new Manifest(in));
					if (!attrs.isEmpty()) {
						o.add("jarManifest", attrs);
					}
				}
			}
			Signing.Result sig = Signing.read(path, zip);
			JsonObject s = new JsonObject();
			JsonArray schemes = new JsonArray();
			sig.schemes().forEach(schemes::add);
			s.add("schemes", schemes);
			JsonArray certs = new JsonArray();
			for (Signing.Cert c : sig.certs()) {
				JsonObject j = new JsonObject();
				j.addProperty("subject", c.subject());
				j.addProperty("issuer", c.issuer());
				j.addProperty("serial", c.serial());
				j.addProperty("notBefore", c.notBefore());
				j.addProperty("notAfter", c.notAfter());
				j.addProperty("algorithm", c.algorithm());
				j.addProperty("key", c.key());
				j.addProperty("sha256", c.sha256());
				j.addProperty("sha1", c.sha1());
				j.addProperty("debug", c.debug());
				certs.add(j);
			}
			s.add("certs", certs);
			o.add("signing", s);
		} catch (IOException e) {
			System.err.println("engine: could not read " + path + " as a zip: " + e);
		}
	}

	private static JsonArray javaVersions(Map<Integer, Integer> byMajor) {
		JsonArray out = new JsonArray();
		new TreeMap<>(byMajor).forEach((major, n) -> {
			if (major < 45) {
				return;
			}
			JsonObject v = new JsonObject();
			// 45..48 are Java 1.1 to 1.4; from 49 on the major is the release plus 44.
			v.addProperty("java", major <= 48 ? "1." + (major - 44) : String.valueOf(major - 44));
			v.addProperty("classes", n);
			out.add(v);
		});
		return out;
	}

	private static int classVersion(Path path) {
		try (InputStream in = Files.newInputStream(path)) {
			byte[] head = in.readNBytes(8);
			return head.length == 8 ? ((head[6] & 0xff) << 8) | (head[7] & 0xff) : 0;
		} catch (IOException e) {
			return 0;
		}
	}

	private static JsonObject jarManifest(Manifest mf) {
		JsonObject o = new JsonObject();
		Attributes main = mf.getMainAttributes();
		for (String key : new String[] {"Main-Class", "Created-By", "Build-Jdk", "Build-Jdk-Spec", "Implementation-Title",
				"Implementation-Version", "Implementation-Vendor", "Automatic-Module-Name", "Multi-Release",
				"Launcher-Agent-Class", "Premain-Class", "Agent-Class", "Start-Class", "Plugin-Class"}) {
			String v = main.getValue(key);
			if (v != null) {
				o.addProperty(key, v.trim());
			}
		}
		return o;
	}

	public static JsonObject android(String xml) throws Exception {
		DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
		f.setNamespaceAware(true);
		f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
		f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		Document doc = f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
		Element root = doc.getDocumentElement();
		String pkg = root.getAttribute("package");

		JsonObject o = new JsonObject();
		o.addProperty("package", pkg);
		put(o, "versionName", a(root, "versionName"));
		put(o, "versionCode", a(root, "versionCode"));
		put(o, "compileSdk", a(root, "compileSdkVersion"));
		Element sdk = first(root, "uses-sdk");
		if (sdk != null) {
			put(o, "minSdk", a(sdk, "minSdkVersion"));
			put(o, "targetSdk", a(sdk, "targetSdkVersion"));
		}
		int target = parseInt(o.has("targetSdk") ? o.get("targetSdk").getAsString() : null, 0);

		JsonArray perms = new JsonArray();
		for (String tag : new String[] {"uses-permission", "uses-permission-sdk-23"}) {
			for (Element e : children(root, tag)) {
				JsonObject p = new JsonObject();
				p.addProperty("name", a(e, "name"));
				put(p, "maxSdk", a(e, "maxSdkVersion"));
				perms.add(p);
			}
		}
		o.add("permissions", perms);
		JsonArray declared = new JsonArray();
		for (Element e : children(root, "permission")) {
			JsonObject p = new JsonObject();
			p.addProperty("name", a(e, "name"));
			put(p, "protectionLevel", a(e, "protectionLevel"));
			declared.add(p);
		}
		o.add("declaredPermissions", declared);
		JsonArray features = new JsonArray();
		for (Element e : children(root, "uses-feature")) {
			String name = a(e, "name");
			if (name != null) {
				features.add(name);
			}
		}
		o.add("features", features);

		Element app = first(root, "application");
		JsonArray components = new JsonArray();
		if (app != null) {
			put(o, "label", a(app, "label"));
			put(o, "application", className(pkg, a(app, "name")));
			put(o, "debuggable", a(app, "debuggable"));
			put(o, "allowBackup", a(app, "allowBackup"));
			put(o, "usesCleartextTraffic", a(app, "usesCleartextTraffic"));
			put(o, "networkSecurityConfig", a(app, "networkSecurityConfig"));
			put(o, "extractNativeLibs", a(app, "extractNativeLibs"));
			for (String tag : COMPONENTS) {
				for (Element e : children(app, tag)) {
					components.add(component(pkg, tag, e, target));
				}
			}
		}
		o.add("components", components);
		return o;
	}

	private static JsonObject component(String pkg, String tag, Element e, int targetSdk) {
		JsonObject c = new JsonObject();
		c.addProperty("type", tag.equals("activity-alias") ? "activity" : tag);
		String name = className(pkg, a(e, tag.equals("activity-alias") ? "targetActivity" : "name"));
		c.addProperty("name", name);
		if (tag.equals("activity-alias")) {
			put(c, "alias", className(pkg, a(e, "name")));
		}
		put(c, "permission", a(e, "permission"));
		put(c, "authorities", a(e, "authorities"));
		JsonArray actions = new JsonArray();
		JsonArray links = new JsonArray();
		boolean launcher = false;
		boolean hasFilter = false;
		for (Element filter : children(e, "intent-filter")) {
			hasFilter = true;
			boolean main = false;
			boolean cat = false;
			boolean view = false;
			for (Element act : children(filter, "action")) {
				String n = a(act, "name");
				if (n != null) {
					actions.add(n);
					main |= n.equals("android.intent.action.MAIN");
					view |= n.equals("android.intent.action.VIEW");
				}
			}
			for (Element category : children(filter, "category")) {
				cat |= "android.intent.category.LAUNCHER".equals(a(category, "name"));
			}
			launcher |= main && cat;
			if (view) {
				for (Element data : children(filter, "data")) {
					String scheme = a(data, "scheme");
					if (scheme == null) {
						continue;
					}
					String host = a(data, "host");
					String p = a(data, "path");
					String prefix = a(data, "pathPrefix");
					String pattern = a(data, "pathPattern");
					String rest = p != null ? p : prefix != null ? prefix + "*" : pattern != null ? pattern : "";
					links.add(scheme + "://" + (host != null ? host : "*") + rest);
				}
			}
		}
		c.add("actions", actions);
		c.add("links", links);
		c.addProperty("launcher", launcher);
		// Before Android 12 a component with an intent filter was exported unless it said otherwise.
		String exported = a(e, "exported");
		boolean isExported = exported != null ? exported.equals("true") : hasFilter && targetSdk < 31 && !tag.equals("provider");
		c.addProperty("exported", isExported);
		c.addProperty("exportedImplicitly", exported == null && isExported);
		return c;
	}

	private static String className(String pkg, String name) {
		if (name == null || name.isEmpty()) {
			return null;
		}
		if (name.startsWith(".")) {
			return pkg + name;
		}
		return name.contains(".") ? name : pkg + "." + name;
	}

	private static String a(Element e, String name) {
		String v = e.getAttributeNS(ANDROID, name);
		return v.isEmpty() ? null : v;
	}

	private static void put(JsonObject o, String key, String value) {
		if (value != null) {
			o.addProperty(key, value);
		}
	}

	private static int parseInt(String s, int fallback) {
		try {
			return s == null ? fallback : Integer.parseInt(s.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static Element first(Element parent, String tag) {
		var list = children(parent, tag);
		return list.isEmpty() ? null : list.get(0);
	}

	private static List<Element> children(Element parent, String tag) {
		List<Element> out = new ArrayList<>();
		NodeList nodes = parent.getChildNodes();
		for (int i = 0; i < nodes.getLength(); i++) {
			Node n = nodes.item(i);
			if (n instanceof Element e && tag.equals(e.getTagName())) {
				out.add(e);
			}
		}
		return out;
	}
}
