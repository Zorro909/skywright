package de.zorro909.skywright.backend.runstore;

import java.util.List;

/** The production S3 behavior every resolved Run Store destination must provide. */
public final class RunStoreS3CapabilityFloor {

	private static final List<String> REQUIRED = List.of("put-object", "conditional-create", "conditional-replace",
			"multipart-create", "multipart-upload", "multipart-list-parts", "multipart-complete", "multipart-abort",
			"list-multipart-uploads", "ranged-read", "list-objects", "delete-object", "read-after-write",
			"list-after-write", "list-after-delete", "get-presigning", "metadata-preservation", "checksum-preservation",
			"cleanup");

	private RunStoreS3CapabilityFloor() {
	}

	public static List<String> requiredCapabilities() {
		return REQUIRED;
	}

}
