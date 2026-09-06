package de.zorro909.skywright.backend.runstore;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.Consumer;

/**
 * Caller-owned stream. Acknowledge successful consumption before closing its response.
 */
public final class RunStoreContent implements AutoCloseable {

	private final RunStoreObjectMetadata descriptor;

	private final InputStream stream;

	private final Consumer<Boolean> completion;

	private boolean accepted;

	private boolean closed;

	public RunStoreContent(RunStoreObjectMetadata descriptor, InputStream stream) {
		this(descriptor, stream, ignored -> {
		});
	}

	RunStoreContent(RunStoreObjectMetadata descriptor, InputStream stream, Consumer<Boolean> completion) {
		this.descriptor = descriptor;
		this.stream = stream;
		this.completion = completion;
	}

	public RunStoreObjectMetadata descriptor() {
		return this.descriptor;
	}

	public InputStream stream() {
		return this.stream;
	}

	/** Call only after the consumer's size and integrity checks have passed. */
	public void accept() {
		this.accepted = true;
	}

	@Override
	public void close() throws IOException {
		if (this.closed) {
			return;
		}
		this.closed = true;
		boolean released = false;
		try {
			this.stream.close();
			released = true;
		}
		finally {
			this.completion.accept(this.accepted && released);
		}
	}

}
