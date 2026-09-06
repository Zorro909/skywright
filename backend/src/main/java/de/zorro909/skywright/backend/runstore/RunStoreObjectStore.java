package de.zorro909.skywright.backend.runstore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;

/** Provider seam separating bounded metadata discovery from content consumption. */
public interface RunStoreObjectStore {

	RunStoreObjectPage list(String prefix, int limit, String continuation);

	RunStoreObjectMetadata head(String key);

	RunStoreContent open(String key);

	URI presignGet(String key, int expiresInSeconds, String contentType, String filename);

	/** Small control records only. Larger consumers must stream with open. */
	default RunStoreObject get(String key) {
		try (RunStoreContent content = open(key)) {
			if (content == null) {
				return null;
			}
			int limit = 16 * 1024 * 1024;
			if (content.descriptor().size() > limit) {
				throw new RunStoreIntegrityException("RUN_STORE_READ_BUDGET: control record exceeds 16 MiB");
			}
			byte[] bytes = content.stream().readNBytes(limit + 1);
			if (bytes.length > limit || bytes.length != content.descriptor().size()) {
				throw new RunStoreIntegrityException("RUN_STORE_DIGEST_MISMATCH: content length differs");
			}
			RunStoreObject result = new RunStoreObject(key, bytes, content.descriptor().contentType(),
					content.descriptor().metadata());
			content.accept();
			return result;
		}
		catch (IOException failure) {
			throw new UncheckedIOException(failure);
		}
	}

}
