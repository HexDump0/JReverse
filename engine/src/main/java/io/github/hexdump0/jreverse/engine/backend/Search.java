package io.github.hexdump0.jreverse.engine.backend;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import jadx.api.JavaNode;

import io.github.hexdump0.jreverse.engine.rpc.ErrorCode;
import io.github.hexdump0.jreverse.engine.rpc.RpcException;

/**
 * Finds text in names, decompiled code and string literals. Code search
 * decompiles every class once (jadx caches the result), so the first search in
 * a big APK is slow and reports progress; later ones are fast.
 */
public final class Search {

	public enum Scope {
		CLASSES, MEMBERS, CODE, STRINGS
	}

	/**
	 * @param type {@code class}, {@code method} or {@code field} for name hits; {@code code} or {@code string}
	 *             (inside a string literal) for code hits
	 * @param node for name hits, what matched
	 * @param cls  the top-level class to open
	 * @param line for code hits, 0-based; -1 for name hits
	 */
	public record Hit(String type, NodeInfo node, String cls, int line, int col, int len, String text) {
	}

	public record Result(List<Hit> hits, boolean truncated, int searched, long ms) {
	}

	private static final int THREADS = Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors()));

	private final JadxBackend jadx;
	private final Pattern pattern;
	private final Set<Scope> scopes;
	private final int limit;

	public Search(JadxBackend jadx, String query, boolean regex, boolean caseSensitive, Set<Scope> scopes, int limit)
			throws RpcException {
		if (query.isEmpty()) {
			throw new RpcException(ErrorCode.BAD_REQUEST, "empty query");
		}
		int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
		try {
			this.pattern = Pattern.compile(regex ? query : Pattern.quote(query), flags);
		} catch (PatternSyntaxException e) {
			throw new RpcException(ErrorCode.BAD_REQUEST, "bad pattern: " + e.getDescription());
		}
		this.jadx = jadx;
		this.scopes = scopes;
		this.limit = limit;
	}

	public Result run(Progress progress, BooleanSupplier cancelled) throws RpcException {
		long start = System.nanoTime();
		List<Hit> hits = new ArrayList<>();
		boolean truncated = false;

		if (scopes.contains(Scope.CLASSES) || scopes.contains(Scope.MEMBERS)) {
			for (JavaNode node : jadx.allNodes()) {
				NodeInfo info = Nodes.info(node);
				boolean isClass = info.kind().equals("class");
				if (!scopes.contains(isClass ? Scope.CLASSES : Scope.MEMBERS)) {
					continue;
				}
				// Classes match on their full name so "net.api" finds a package's classes.
				String text = isClass ? info.detail() : info.name();
				if (pattern.matcher(text).find()) {
					if (hits.size() >= limit) {
						truncated = true;
						break;
					}
					hits.add(new Hit(info.kind(), info, info.top(), -1, 0, 0, text));
				}
			}
		}

		int searched = 0;
		if (!truncated && (scopes.contains(Scope.CODE) || scopes.contains(Scope.STRINGS))) {
			boolean stringsOnly = !scopes.contains(Scope.CODE);
			List<ClassEntry> classes = jadx.classes();
			ConcurrentLinkedQueue<Hit> found = new ConcurrentLinkedQueue<>();
			AtomicInteger count = new AtomicInteger(hits.size());
			AtomicInteger done = new AtomicInteger();
			AtomicBoolean full = new AtomicBoolean();
			ForkJoinPool pool = new ForkJoinPool(THREADS);
			try {
				pool.submit(() -> classes.parallelStream().forEach(cls -> {
					if (full.get() || cancelled.getAsBoolean()) {
						return;
					}
					String code;
					try {
						code = jadx.source(cls.id());
					} catch (RpcException e) {
						code = "";
					}
					scan(cls.id(), code, stringsOnly, found, count, full);
					int d = done.incrementAndGet();
					progress.report(d, classes.size());
				})).get();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new RpcException(ErrorCode.CANCELLED, "search interrupted");
			} catch (ExecutionException e) {
				throw new RpcException(ErrorCode.INTERNAL, "search failed: " + e.getCause(), e);
			} finally {
				pool.shutdownNow();
			}
			if (cancelled.getAsBoolean()) {
				throw new RpcException(ErrorCode.CANCELLED, "search cancelled");
			}
			searched = done.get();
			truncated = full.get();
			List<Hit> code = new ArrayList<>(found);
			code.sort(Comparator.comparing(Hit::cls).thenComparingInt(Hit::line).thenComparingInt(Hit::col));
			hits.addAll(code.subList(0, Math.min(code.size(), limit - hits.size())));
		}
		return new Result(hits, truncated, searched, (System.nanoTime() - start) / 1_000_000);
	}

	private void scan(String cls, String code, boolean stringsOnly, ConcurrentLinkedQueue<Hit> found, AtomicInteger count,
			AtomicBoolean full) {
		Matcher quick = pattern.matcher(code);
		if (!quick.find()) {
			return;
		}
		Lines lines = new Lines(code);
		int lastLine = -1;
		do {
			int line = lines.line(quick.start());
			int col = quick.start() - lines.start(line);
			String text = lines.text(line);
			boolean inString = inString(text, col);
			if (stringsOnly && !inString) {
				continue;
			}
			// One hit per line is enough to find it; the editor highlights the rest.
			if (line == lastLine) {
				continue;
			}
			lastLine = line;
			if (count.incrementAndGet() > limit) {
				full.set(true);
				return;
			}
			int len = Math.min(quick.end(), lines.start(line) + text.length()) - quick.start();
			found.add(new Hit(inString ? "string" : "code", null, cls, line, col, Math.max(len, 0), text));
		} while (quick.find());
	}

	/** Whether column {@code col} of a Java source line is inside a string or char literal. */
	static boolean inString(String line, int col) {
		char quote = 0;
		for (int i = 0; i < col && i < line.length(); i++) {
			char c = line.charAt(i);
			if (quote != 0) {
				if (c == '\\') {
					i++;
				} else if (c == quote) {
					quote = 0;
				}
			} else if (c == '"' || c == '\'') {
				quote = c;
			} else if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
				return false;
			}
		}
		return quote != 0;
	}

	public static Scope scope(String name) throws RpcException {
		try {
			return Scope.valueOf(name.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new RpcException(ErrorCode.BAD_REQUEST, "unknown search scope: " + name);
		}
	}
}
