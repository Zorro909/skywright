package de.zorro909.skywright.backend.datasetcatalog;

import java.time.Instant;

public record DatasetCopyGenerationView(long number, String location, String manifestIdentity,
		String contentFingerprint, long verifiedBytes, Instant createdAt, Instant verifiedAt, boolean acceptingLeases,
		DatasetCopyAvailability availability, long activeLeaseCount, Instant lastRunUsedAt) {

	public DatasetCopyGenerationView(long number, String location, String manifestIdentity, String contentFingerprint,
			long verifiedBytes, Instant createdAt, Instant verifiedAt, boolean acceptingLeases,
			DatasetCopyAvailability availability) {
		this(number, location, manifestIdentity, contentFingerprint, verifiedBytes, createdAt, verifiedAt,
				acceptingLeases, availability, 0, null);
	}

	DatasetCopyGenerationView withLeaseFacts(long activeLeaseCount, Instant lastRunUsedAt) {
		return new DatasetCopyGenerationView(this.number, this.location, this.manifestIdentity, this.contentFingerprint,
				this.verifiedBytes, this.createdAt, this.verifiedAt, this.acceptingLeases, this.availability,
				activeLeaseCount, lastRunUsedAt);
	}
}
