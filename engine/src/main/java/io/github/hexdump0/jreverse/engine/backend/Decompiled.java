package io.github.hexdump0.jreverse.engine.backend;

/** @param warnings number of places the decompiler flagged its own output */
public record Decompiled(String source, int warnings) {
}
