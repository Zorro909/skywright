package de.zorro909.skywright.backend.runstore;

import java.io.IOException;
import java.io.InputStream;

/** Caller-owned stream. Closing it releases the provider response. */
public record RunStoreContent(RunStoreObjectMetadata descriptor, InputStream stream) implements AutoCloseable {
	@Override
	public void close() throws IOException {
		stream.close();
	}
}
