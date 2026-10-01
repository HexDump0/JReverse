package io.github.hexdump0.jreverse.engine.backend;

/**
 * A top-level class in the input.
 *
 * @param id original internal name ({@code com/foo/Bar}); never changes on rename
 */
public record ClassEntry(String id, ClassKind kind) {
}
