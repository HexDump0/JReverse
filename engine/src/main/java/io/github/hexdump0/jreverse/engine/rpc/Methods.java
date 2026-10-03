package io.github.hexdump0.jreverse.engine.rpc;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.github.hexdump0.jreverse.engine.backend.ArchiveFiles;
import io.github.hexdump0.jreverse.engine.backend.Backend;
import io.github.hexdump0.jreverse.engine.backend.ClassEntry;
import io.github.hexdump0.jreverse.engine.backend.Decompiled;
import io.github.hexdump0.jreverse.engine.backend.JadxBackend;
import io.github.hexdump0.jreverse.engine.backend.NodeInfo;
import io.github.hexdump0.jreverse.engine.backend.Progress;
import io.github.hexdump0.jreverse.engine.backend.Search;
import io.github.hexdump0.jreverse.engine.session.Export;
import io.github.hexdump0.jreverse.engine.session.Session;
import io.github.hexdump0.jreverse.engine.session.Sessions;

/** Request handlers. Each takes the request's params and returns its result. */
final class Methods {

	private static final int DEFAULT_SEARCH_LIMIT = 1000;
	private static final long PROGRESS_EVERY_MS = 100;

	private final Sessions sessions;
	private final BiConsumer<String, JsonObject> notify;
	/** Long jobs by the ticket the client gave them, so {@code cancel} can stop them. */
	private final Map<String, AtomicBoolean> running = new ConcurrentHashMap<>();

	Methods(Sessions sessions, BiConsumer<String, JsonObject> notify) {
		this.sessions = sessions;
		this.notify = notify;
	}

	JsonElement open(JsonObject params) throws RpcException {
		String raw = string(params, "path");
		Path path;
		try {
			path = Path.of(raw).toAbsolutePath().normalize();
		} catch (InvalidPathException e) {
			throw new RpcException(ErrorCode.BAD_REQUEST, "invalid path: " + raw);
		}
		if (!Files.isRegularFile(path)) {
			throw new RpcException(ErrorCode.OPEN_FAILED, "no such file: " + path);
		}
		long start = System.nanoTime();
		Session session = sessions.open(path, bool(params, "deobfuscate"));
		JsonObject result = new JsonObject();
		result.addProperty("session", session.id());
		result.addProperty("kind", session.kind().wireName());
		result.addProperty("deobfuscated", session.deobfuscated());
		result.addProperty("classCount", session.classes().size());
		JsonArray engines = new JsonArray();
		session.engines().forEach(engines::add);
		result.add("engines", engines);
		result.addProperty("ms", millisSince(start));
		return result;
	}

	JsonElement listClasses(JsonObject params) throws RpcException {
		Session session = sessions.get(string(params, "session"));
		JsonArray list = new JsonArray(session.classes().size());
		for (ClassEntry cls : session.classes()) {
			JsonObject o = new JsonObject();
			o.addProperty("id", cls.id());
			o.addProperty("kind", cls.kind().wireName());
			// Only when it differs from the id's own name, e.g. a deobfuscated C0123a.
			String name = session.jadx().displayName(cls.id());
			if (!cls.id().endsWith("/" + name) && !cls.id().equals(name)) {
				o.addProperty("name", name);
			}
			list.add(o);
		}
		return list;
	}

	JsonElement decompile(JsonObject params) throws RpcException {
		Session session = sessions.get(string(params, "session"));
		String classId = string(params, "class");
		String engine = params.has("engine") ? string(params, "engine") : Sessions.DEFAULT_ENGINE;
		Backend backend = session.backend(engine);
		long start = System.nanoTime();
		Decompiled out = backend.decompile(classId);
		JsonObject result = new JsonObject();
		result.addProperty("source", out.source());
		result.addProperty("engine", backend.id());
		result.addProperty("ms", millisSince(start));
		result.addProperty("warnings", out.warnings());
		result.add("links", spans(out.links()));
		result.add("decls", spans(out.decls()));
		JsonArray nodes = new JsonArray(out.nodes().size());
		out.nodes().forEach(n -> nodes.add(node(n)));
		result.add("nodes", nodes);
		return result;
	}

	JsonElement smali(JsonObject params) throws RpcException {
		JadxBackend jadx = sessions.get(string(params, "session")).jadx();
		long start = System.nanoTime();
		String source = jadx.smali(string(params, "class"));
		JsonObject result = new JsonObject();
		result.addProperty("source", source);
		result.addProperty("ms", millisSince(start));
		return result;
	}

	JsonElement node(JsonObject params) throws RpcException {
		return node(sessions.get(string(params, "session")).jadx().nodeInfo(string(params, "node")));
	}

	JsonElement usages(JsonObject params) throws RpcException {
		JadxBackend jadx = sessions.get(string(params, "session")).jadx();
		long start = System.nanoTime();
		List<JadxBackend.Usage> found = jadx.usages(string(params, "node"));
		JsonArray list = new JsonArray(found.size());
		for (JadxBackend.Usage u : found) {
			JsonObject o = new JsonObject();
			o.addProperty("cls", u.cls());
			o.addProperty("line", u.line());
			o.addProperty("col", u.col());
			o.addProperty("len", u.len());
			o.addProperty("text", u.text());
			if (u.in() != null) {
				o.add("in", node(u.in()));
			}
			list.add(o);
		}
		JsonObject result = new JsonObject();
		result.add("usages", list);
		result.addProperty("ms", millisSince(start));
		return result;
	}

	JsonElement search(JsonObject params) throws RpcException {
		JadxBackend jadx = sessions.get(string(params, "session")).jadx();
		Set<Search.Scope> scopes = EnumSet.noneOf(Search.Scope.class);
		if (params.has("scopes") && params.get("scopes").isJsonArray()) {
			for (JsonElement s : params.getAsJsonArray("scopes")) {
				scopes.add(Search.scope(s.getAsString()));
			}
		}
		if (scopes.isEmpty()) {
			scopes = EnumSet.allOf(Search.Scope.class);
		}
		int limit = params.has("limit") ? params.get("limit").getAsInt() : DEFAULT_SEARCH_LIMIT;
		Search search = new Search(jadx, string(params, "query"), bool(params, "regex"), bool(params, "caseSensitive"), scopes,
				Math.max(1, limit));
		String ticket = optString(params, "ticket");
		AtomicBoolean cancelled = start(ticket);
		try {
			Search.Result r = search.run(progress(ticket), cancelled::get);
			JsonArray hits = new JsonArray(r.hits().size());
			for (Search.Hit h : r.hits()) {
				JsonObject o = new JsonObject();
				o.addProperty("type", h.type());
				o.addProperty(h.type().equals("file") ? "path" : "cls", h.cls());
				if (h.node() != null) {
					o.add("node", node(h.node()));
				} else {
					o.addProperty("line", h.line());
					o.addProperty("col", h.col());
					o.addProperty("len", h.len());
					o.addProperty("text", h.text());
				}
				hits.add(o);
			}
			JsonObject result = new JsonObject();
			result.add("hits", hits);
			result.addProperty("truncated", r.truncated());
			result.addProperty("searched", r.searched());
			result.addProperty("ms", r.ms());
			return result;
		} finally {
			finish(ticket);
		}
	}

	JsonElement export(JsonObject params) throws RpcException {
		JadxBackend jadx = sessions.get(string(params, "session")).jadx();
		Path dir;
		try {
			dir = Path.of(string(params, "dir")).toAbsolutePath().normalize();
		} catch (InvalidPathException e) {
			throw new RpcException(ErrorCode.BAD_REQUEST, "invalid path: " + params.get("dir"));
		}
		String ticket = optString(params, "ticket");
		AtomicBoolean cancelled = start(ticket);
		try {
			Export.Result r = Export.sources(jadx, dir, progress(ticket), cancelled::get);
			JsonObject result = new JsonObject();
			result.addProperty("dir", dir.toString());
			result.addProperty("written", r.written());
			result.addProperty("failed", r.failed());
			result.addProperty("ms", r.ms());
			return result;
		} finally {
			finish(ticket);
		}
	}

	JsonElement cancel(JsonObject params) throws RpcException {
		AtomicBoolean flag = running.get(string(params, "ticket"));
		if (flag != null) {
			flag.set(true);
		}
		JsonObject result = new JsonObject();
		result.addProperty("cancelled", flag != null);
		return result;
	}

	JsonElement overview(JsonObject params) throws RpcException {
		return sessions.get(string(params, "session")).overview();
	}

	JsonElement setCodeData(JsonObject params) throws RpcException {
		JadxBackend jadx = sessions.get(string(params, "session")).jadx();
		int applied = jadx.setCodeData(stringMap(params, "renames"), stringMap(params, "comments"));
		JsonObject result = new JsonObject();
		result.addProperty("applied", applied);
		return result;
	}

	JsonElement files(JsonObject params) throws RpcException {
		ArchiveFiles files = sessions.get(string(params, "session")).jadx().files();
		JsonArray list = new JsonArray();
		for (ArchiveFiles.Entry e : files.list()) {
			JsonObject o = new JsonObject();
			o.addProperty("path", e.path());
			o.addProperty("type", e.type());
			o.addProperty("size", e.size());
			list.add(o);
		}
		JsonObject result = new JsonObject();
		result.add("files", list);
		return result;
	}

	JsonElement file(JsonObject params) throws RpcException {
		ArchiveFiles.Content c = sessions.get(string(params, "session")).jadx().files().read(string(params, "path"));
		JsonObject o = new JsonObject();
		o.addProperty("path", c.path());
		o.addProperty("kind", c.kind());
		o.addProperty("size", c.size());
		if (c.text() != null) {
			o.addProperty("text", c.text());
		}
		if (c.data() != null) {
			o.addProperty("data", c.data());
		}
		if (c.mime() != null) {
			o.addProperty("mime", c.mime());
		}
		if (!c.children().isEmpty()) {
			JsonArray children = new JsonArray();
			c.children().forEach(children::add);
			o.add("children", children);
		}
		o.addProperty("truncated", c.truncated());
		return o;
	}

	JsonElement close(JsonObject params) throws RpcException {
		sessions.close(string(params, "session"));
		return new JsonObject();
	}

	private AtomicBoolean start(String ticket) throws RpcException {
		AtomicBoolean flag = new AtomicBoolean();
		if (ticket != null && running.putIfAbsent(ticket, flag) != null) {
			throw new RpcException(ErrorCode.BAD_REQUEST, "ticket already running: " + ticket);
		}
		return flag;
	}

	private void finish(String ticket) {
		if (ticket != null) {
			running.remove(ticket);
		}
	}

	/** Sends {@code progress} notifications for a ticket, at most every 100 ms plus the last one. */
	private Progress progress(String ticket) {
		if (ticket == null) {
			return Progress.NONE;
		}
		long[] last = {0};
		return (done, total) -> {
			long now = System.nanoTime() / 1_000_000;
			synchronized (last) {
				if (done < total && now - last[0] < PROGRESS_EVERY_MS) {
					return;
				}
				last[0] = now;
			}
			JsonObject p = new JsonObject();
			p.addProperty("ticket", ticket);
			p.addProperty("done", done);
			p.addProperty("total", total);
			notify.accept("progress", p);
		};
	}

	/** Spans as a flat array, four numbers each: line, column, length, node index. */
	private static JsonArray spans(List<Decompiled.Span> spans) {
		JsonArray a = new JsonArray(spans.size() * 4);
		for (Decompiled.Span s : spans) {
			a.add(s.line());
			a.add(s.col());
			a.add(s.len());
			a.add(s.node());
		}
		return a;
	}

	private static JsonObject node(NodeInfo n) {
		JsonObject o = new JsonObject();
		o.addProperty("kind", n.kind());
		o.addProperty("id", n.id());
		o.addProperty("top", n.top());
		o.addProperty("name", n.name());
		o.addProperty("detail", n.detail());
		o.addProperty("access", n.access());
		o.addProperty("static", n.isStatic());
		if (n.kind().equals("method")) {
			JsonArray frida = new JsonArray();
			n.frida().forEach(frida::add);
			o.add("frida", frida);
		}
		return o;
	}

	private static String string(JsonObject params, String name) throws RpcException {
		JsonElement el = params.get(name);
		if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
			throw new RpcException(ErrorCode.BAD_REQUEST, "missing string param: " + name);
		}
		return el.getAsString();
	}

	private static String optString(JsonObject params, String name) throws RpcException {
		return params.has(name) && !params.get(name).isJsonNull() ? string(params, name) : null;
	}

	private static boolean bool(JsonObject params, String name) {
		JsonElement el = params.get(name);
		return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean() && el.getAsBoolean();
	}

	private static Map<String, String> stringMap(JsonObject params, String name) throws RpcException {
		Map<String, String> out = new LinkedHashMap<>();
		JsonElement el = params.get(name);
		if (el == null || el.isJsonNull()) {
			return out;
		}
		if (!el.isJsonObject()) {
			throw new RpcException(ErrorCode.BAD_REQUEST, name + " must be an object");
		}
		for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
			if (!e.getValue().isJsonPrimitive()) {
				throw new RpcException(ErrorCode.BAD_REQUEST, name + "." + e.getKey() + " must be a string");
			}
			out.put(e.getKey(), e.getValue().getAsString());
		}
		return out;
	}

	private static long millisSince(long startNanos) {
		return (System.nanoTime() - startNanos) / 1_000_000;
	}
}
