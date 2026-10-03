package com.example.vault.store;

import java.util.LinkedHashMap;
import java.util.Map;

import com.example.vault.license.License;

public final class Vault {

	private final License license;
	private final Map<String, Entry> entries = new LinkedHashMap<>();
	private final Cipher cipher = new Cipher() {
		@Override
		public String seal(String plain) {
			return new StringBuilder(plain).reverse().toString();
		}

		@Override
		public String open(String sealed) {
			return seal(sealed);
		}
	};

	public Vault(License license) {
		this.license = license;
	}

	public boolean put(String name, String secret) {
		if (entries.size() >= license.edition().limit()) {
			return false;
		}
		entries.put(name, new Entry(name, cipher.seal(secret)));
		return true;
	}

	public String get(String name) {
		Entry e = entries.get(name);
		return e == null ? null : cipher.open(e.sealed);
	}

	public int size() {
		return entries.size();
	}

	private static final class Entry {
		final String name;
		final String sealed;

		Entry(String name, String sealed) {
			this.name = name;
			this.sealed = sealed;
		}
	}
}
