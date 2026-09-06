package de.zorro909.skywright.backend.runstore;

/** Immutable Artifact or Sample summary safe to expose without decoding payload bytes. */
public record RunStoreOutput(RunStoreOutputKind kind, long step, String name, String key, long size, String contentType,
		String digest) {
	public RunStoreDownloadLink.Verification verification() {
		return RunStoreDownloadLink.Verification.NOT_RECORDED;
	}
}
