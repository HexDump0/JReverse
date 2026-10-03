package io.github.hexdump0.jreverse.engine.backend;

import java.util.List;

/**
 * @param warnings number of places the decompiler flagged its own output
 * @param links    identifiers in {@code source} that name a class, method or field; empty if the backend can't tell
 * @param decls    the subset of positions where one is declared
 * @param nodes    what {@code links} and {@code decls} point at, by index
 */
public record Decompiled(String source, int warnings, List<Span> links, List<Span> decls, List<NodeInfo> nodes) {

	public Decompiled(String source, int warnings) {
		this(source, warnings, List.of(), List.of(), List.of());
	}

	/** A run of {@code len} UTF-16 units at a 0-based line and column, pointing at {@code nodes[node]}. */
	public record Span(int line, int col, int len, int node) {
	}
}
