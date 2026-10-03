package io.github.hexdump0.jreverse.engine.session;

import java.util.Locale;

public enum InputKind {
	APK,
	AAB,
	AAR,
	JAR,
	DEX,
	CLASS;

	public String wireName() {
		return name().toLowerCase(Locale.ROOT);
	}
}
