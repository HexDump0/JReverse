package io.github.hexdump0.jreverse.engine;

public final class Version {

	/** Bumped on any incompatible change to the wire protocol. */
	public static final int PROTOCOL = 1;

	private Version() {
	}

	public static String engine() {
		String v = Version.class.getPackage().getImplementationVersion();
		return v != null ? v : "dev";
	}
}
