package io.github.hexdump0.jreverse.engine.backend;

import java.util.List;

/**
 * A class, method or field that code links point at.
 *
 * @param kind   {@code class}, {@code method} or {@code field}
 * @param id     {@code com/foo/Bar}, {@code com/foo/Bar.run(I)V} or {@code com/foo/Bar.count:I}; never changes on rename
 * @param top    the top-level class whose source declares it, i.e. where to go to see it
 * @param name   display name, after renames
 * @param detail one-line signature, e.g. {@code run(int): void}
 * @param access {@code public}, {@code protected}, {@code private} or {@code ""}
 * @param isStatic whether it is static
 * @param frida  argument types for Frida's {@code overload()}, methods only
 */
public record NodeInfo(String kind, String id, String top, String name, String detail, String access, boolean isStatic,
		List<String> frida) {
}
