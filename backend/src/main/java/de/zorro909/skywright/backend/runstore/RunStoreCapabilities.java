package de.zorro909.skywright.backend.runstore;

import java.util.List;

/** Production S3 behavior required by the Run Store protocol. */
public final class RunStoreCapabilities {

	private static final List<String> REQUIRED_S3 = List.of("put-object", "conditional-create", "conditional-replace",
			"multipart-create", "multipart-upload", "multipart-list-parts", "multipart-complete", "multipart-abort",
			"list-multipart-uploads", "ranged-read", "list-objects", "delete-object", "read-after-write",
			"list-after-write", "list-after-delete", "get-presigning", "metadata-preservation", "checksum-preservation",
			"cleanup");

	private RunStoreCapabilities() {
	}

	public static List<String> requiredS3Capabilities() {
		return REQUIRED_S3;
	}

}
