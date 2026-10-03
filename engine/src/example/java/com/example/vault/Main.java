package com.example.vault;

import com.example.vault.license.Edition;
import com.example.vault.license.License;
import com.example.vault.license.LicenseCheck;
import com.example.vault.store.Vault;

/** JReverse's bundled example: a tiny password vault that unlocks with a license key. */
public final class Main {

	private Main() {
	}

	public static void main(String[] args) {
		if (args.length < 2) {
			System.err.println("usage: vault <owner> <license-key>");
			System.exit(2);
		}
		License license = LicenseCheck.verify(args[0], args[1]);
		Vault vault = new Vault(license);
		vault.put("mail", "correct horse battery staple");
		vault.put("bank", "hunter2");
		System.out.println(license.edition() == Edition.PRO
				? "Unlocked for " + license.owner() + ". " + vault.size() + " entries."
				: "Free edition: " + vault.size() + " of " + Edition.FREE.limit() + " entries.");
	}
}
