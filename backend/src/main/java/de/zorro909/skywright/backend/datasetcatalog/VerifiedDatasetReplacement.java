package de.zorro909.skywright.backend.datasetcatalog;

import java.time.Instant;

public record VerifiedDatasetReplacement(String location, long verifiedBytes, String manifestIdentity,
		String contentFingerprint, Instant verifiedAt) {
}
