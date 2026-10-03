package io.github.hexdump0.jreverse.engine.backend;

/** Told how far a long job over every class has got. Called from worker threads. */
@FunctionalInterface
public interface Progress {

	Progress NONE = (done, total) -> {
	};

	void report(int done, int total);
}
