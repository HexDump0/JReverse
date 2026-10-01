package io.github.hexdump0.jreverse.engine.backend;

import java.util.Locale;

public enum ClassKind {
	CLASS,
	INTERFACE,
	ENUM,
	ANNOTATION,
	RECORD;

	public String wireName() {
		return name().toLowerCase(Locale.ROOT);
	}
}
