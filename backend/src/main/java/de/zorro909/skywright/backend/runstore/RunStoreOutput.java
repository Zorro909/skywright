package de.zorro909.skywright.backend.runstore;

/** Immutable Artifact or Sample summary safe to expose without decoding payload bytes. */
public record RunStoreOutput(String kind, long step, String name, String key, long size, String contentType,
		String digest) {
}
