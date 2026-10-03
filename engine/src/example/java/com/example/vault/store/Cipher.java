package com.example.vault.store;

/** How entries are scrambled at rest. */
public interface Cipher {

	String seal(String plain);

	String open(String sealed);
}
