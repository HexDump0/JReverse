package io.github.hexdump0.jreverse.engine.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import io.github.hexdump0.jreverse.engine.backend.ClassEntry;
import io.github.hexdump0.jreverse.engine.backend.JadxBackend;
import io.github.hexdump0.jreverse.engine.backend.Progress;
import io.github.hexdump0.jreverse.engine.rpc.ErrorCode;
import io.github.hexdump0.jreverse.engine.rpc.RpcException;

/** Writes every class's decompiled source under a folder, one {@code .java} file per top-level class. */
public final class Export {

	public record Result(int written, int failed, long ms) {
	}

	private Export() {
	}

	public static Result sources(JadxBackend jadx, Path dir, Progress progress, BooleanSupplier cancelled)
			throws RpcException {
		long start = System.nanoTime();
		try {
			Files.createDirectories(dir);
		} catch (IOException e) {
			throw new RpcException(ErrorCode.EXPORT_FAILED, "cannot create " + dir + ": " + e.getMessage(), e);
		}
		List<ClassEntry> classes = jadx.classes();
		AtomicInteger written = new AtomicInteger();
		AtomicInteger failed = new AtomicInteger();
		AtomicInteger done = new AtomicInteger();
		ForkJoinPool pool = new ForkJoinPool(Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors())));
		try {
			pool.submit(() -> classes.parallelStream().forEach(cls -> {
				if (cancelled.getAsBoolean()) {
					return;
				}
				Path out = dir.resolve(cls.id() + ".java").normalize();
				try {
					if (!out.startsWith(dir)) {
						throw new IOException("class name escapes the folder");
					}
					Files.createDirectories(out.getParent());
					Files.writeString(out, jadx.source(cls.id()));
					written.incrementAndGet();
				} catch (IOException | RpcException e) {
					failed.incrementAndGet();
					System.err.println("engine: export skipped " + cls.id() + ": " + e.getMessage());
				}
				progress.report(done.incrementAndGet(), classes.size());
			})).get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RpcException(ErrorCode.CANCELLED, "export interrupted");
		} catch (ExecutionException e) {
			throw new RpcException(ErrorCode.EXPORT_FAILED, "export failed: " + e.getCause(), e);
		} finally {
			pool.shutdownNow();
		}
		if (cancelled.getAsBoolean()) {
			throw new RpcException(ErrorCode.CANCELLED, "export cancelled after " + written.get() + " files");
		}
		return new Result(written.get(), failed.get(), (System.nanoTime() - start) / 1_000_000);
	}
}
