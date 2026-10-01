package io.github.hexdump0.jreverse.engine.backend;

import java.util.List;

import io.github.hexdump0.jreverse.engine.rpc.RpcException;

/** One decompiler loaded over one input file. Implementations must be thread-safe. */
public interface Backend extends AutoCloseable {

	/** Engine name used on the wire, e.g. {@code "jadx"}. */
	String id();

	/** Top-level classes, sorted by id. */
	List<ClassEntry> classes();

	Decompiled decompile(String classId) throws RpcException;

	@Override
	void close();
}
