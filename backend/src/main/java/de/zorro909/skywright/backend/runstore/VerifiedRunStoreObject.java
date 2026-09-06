package de.zorro909.skywright.backend.runstore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Locally staged, checksum-verified bytes. The caller owns and closes this file. */
public record VerifiedRunStoreObject(Path path, RunStoreObjectMetadata descriptor) implements AutoCloseable {
	@Override
	public void close() throws IOException {
		Files.deleteIfExists(path);
	}
}
