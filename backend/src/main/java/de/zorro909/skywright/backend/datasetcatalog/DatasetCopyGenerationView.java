package de.zorro909.skywright.backend.datasetcatalog;

import java.time.Instant;

public record DatasetCopyGenerationView(long number, String location, String manifestIdentity,
		String contentFingerprint, long verifiedBytes, Instant createdAt, Instant verifiedAt, boolean acceptingLeases,
		DatasetCopyAvailability availability) {
}
