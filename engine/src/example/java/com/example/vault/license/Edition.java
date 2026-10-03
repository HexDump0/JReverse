package com.example.vault.license;

public enum Edition {
	FREE(1),
	PRO(Integer.MAX_VALUE);

	private final int limit;

	Edition(int limit) {
		this.limit = limit;
	}

	public int limit() {
		return limit;
	}
}
