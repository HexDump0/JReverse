package io.github.hexdump0.jreverse.engine;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;

import io.github.hexdump0.jreverse.engine.rpc.Server;

/**
 * Process entry point. Speaks the engine protocol (newline-delimited JSON) on
 * stdin/stdout; everything else, logging included, goes to stderr.
 */
public final class Main {

	private Main() {
	}

	public static void main(String[] args) {
		// Take the real stdout for the protocol before any library can print to it.
		PrintStream protocolOut = new PrintStream(new FileOutputStream(FileDescriptor.out), false);
		System.setOut(System.err);

		new Server(System.in, protocolOut).run();
		// jadx can leave non-daemon worker threads behind.
		System.exit(0);
	}
}
