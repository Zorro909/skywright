package de.zorro909.skywright.backend.datasetcatalog;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.Instant;
import java.util.UUID;

@Embeddable
class DatasetGenerationEmbeddable {

	@Column(name = "copy_id", nullable = false)
	UUID copyId;

	@Column(name = "generation_number", nullable = false)
	long number;

	@Column(nullable = false, length = 2048)
	String location;

	@Column(name = "manifest_identity", nullable = false)
	String manifestIdentity;

	@Column(name = "content_fingerprint", nullable = false)
	String contentFingerprint;

	@Column(name = "verified_bytes", nullable = false)
	long verifiedBytes;

	@Column(name = "created_at", nullable = false)
	Instant createdAt;

	@Column(name = "verified_at", nullable = false)
	Instant verifiedAt;

	@Column(name = "accepting_leases", nullable = false)
	boolean acceptingLeases;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	DatasetCopyAvailability availability;

	protected DatasetGenerationEmbeddable() {
	}

	static DatasetGenerationEmbeddable from(UUID copyId, DatasetCopyGenerationView value) {
		DatasetGenerationEmbeddable result = new DatasetGenerationEmbeddable();
		result.copyId = copyId;
		result.number = value.number();
		result.location = value.location();
		result.manifestIdentity = value.manifestIdentity();
		result.contentFingerprint = value.contentFingerprint();
		result.verifiedBytes = value.verifiedBytes();
		result.createdAt = value.createdAt();
		result.verifiedAt = value.verifiedAt();
		result.acceptingLeases = value.acceptingLeases();
		result.availability = value.availability();
		return result;
	}

	DatasetCopyGenerationView domain() {
		return new DatasetCopyGenerationView(this.number, this.location, this.manifestIdentity, this.contentFingerprint,
				this.verifiedBytes, this.createdAt, this.verifiedAt, this.acceptingLeases, this.availability);
	}

}
