package de.zorro909.skywright.backend.runstore;

import java.util.Map;

/** Exact immutable object loaded with its protocol metadata. */
public record RunStoreObject(String key, byte[] bytes, String contentType, Map<String, String> metadata) {

	public RunStoreObject {
		bytes = bytes.clone();
		metadata = Map.copyOf(metadata);
	}

	@Override
	public byte[] bytes() {
		return this.bytes.clone();
	}

}
