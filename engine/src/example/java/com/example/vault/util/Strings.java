package com.example.vault.util;

public final class Strings {

	private static final int KEY = 0x4a;

	private Strings() {
	}

	public static String decode(byte[] data) {
		char[] out = new char[data.length];
		for (int i = 0; i < data.length; i++) {
			out[i] = (char) (data[i] ^ KEY);
		}
		return new String(out);
	}
}
