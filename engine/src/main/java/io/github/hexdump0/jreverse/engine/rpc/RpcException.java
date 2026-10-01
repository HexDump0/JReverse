package io.github.hexdump0.jreverse.engine.rpc;

/** An error reported to the client as {@code {"error":{"code","message"}}}. */
public class RpcException extends Exception {

	private final ErrorCode code;

	public RpcException(ErrorCode code, String message) {
		super(message);
		this.code = code;
	}

	public RpcException(ErrorCode code, String message, Throwable cause) {
		super(message, cause);
		this.code = code;
	}

	public ErrorCode code() {
		return code;
	}
}
