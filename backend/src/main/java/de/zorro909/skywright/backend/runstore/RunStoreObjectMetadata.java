package de.zorro909.skywright.backend.runstore;

import java.util.Map;

/** Provider metadata; the checksum is expected, not proof of a fresh content read. */
public record RunStoreObjectMetadata(String key, long size, String contentType, Map<String, String> metadata) {
	public RunStoreObjectMetadata {
		metadata = Map.copyOf(metadata);
	}

	public RunStoreDownloadLink.Verification verification() {
		return RunStoreDownloadLink.Verification.NOT_RECORDED;
	}
}
