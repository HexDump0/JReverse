package com.example.vault.license;

import com.example.vault.util.Strings;

/**
 * Keys look like {@code VLT-1234-ABCD}: a fixed prefix, four digits that add up
 * to 21, and a tag derived from the owner's name.
 */
public final class LicenseCheck {

	// "VLT-", stored XOR-ed so it doesn't show up in a strings dump.
	private static final byte[] PREFIX = {0x1c, 0x06, 0x1e, 0x67};

	private LicenseCheck() {
	}

	public static License verify(String owner, String key) {
		String prefix = Strings.decode(PREFIX);
		if (key == null || !key.startsWith(prefix) || key.length() != prefix.length() + 9) {
			return free(owner);
		}
		String[] parts = key.substring(prefix.length()).split("-");
		if (parts.length != 2 || digitSum(parts[0]) != 21) {
			return free(owner);
		}
		return parts[1].equals(tag(owner)) ? new License(owner, Edition.PRO) : free(owner);
	}

	private static int digitSum(String s) {
		int sum = 0;
		for (char c : s.toCharArray()) {
			if (!Character.isDigit(c)) {
				return -1;
			}
			sum += c - '0';
		}
		return sum;
	}

	static String tag(String owner) {
		int h = 0x5f3759df;
		for (char c : owner.toLowerCase().toCharArray()) {
			h = Integer.rotateLeft(h ^ c, 7) * 31;
		}
		return String.format("%04X", h & 0xffff);
	}

	private static License free(String owner) {
		return new License(owner, Edition.FREE);
	}
}
