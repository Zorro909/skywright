package de.zorro909.skywright.backend.datasetcatalog;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.Instant;
import java.util.UUID;

@Embeddable
class DatasetCacheEmbeddable {

	@Column(name = "cache_id", nullable = false)
	UUID id;

	@Enumerated(EnumType.STRING)
	@Column(name = "owner_type", nullable = false)
	DatasetCacheOwnerType ownerType;

	@Column(name = "owner_id", nullable = false)
	String ownerId;

	@Column(name = "measured_bytes", nullable = false)
	long measuredBytes;

	@Column(name = "verified_at", nullable = false)
	Instant verifiedAt;

	@Column(name = "last_used_at", nullable = false)
	Instant lastUsedAt;

	@Column(name = "created_at", nullable = false)
	Instant createdAt;

	protected DatasetCacheEmbeddable() {
	}

	static DatasetCacheEmbeddable from(DatasetCacheView value) {
		DatasetCacheEmbeddable result = new DatasetCacheEmbeddable();
		result.id = value.id();
		result.ownerType = value.ownerType();
		result.ownerId = value.ownerId();
		result.measuredBytes = value.measuredBytes();
		result.verifiedAt = value.verifiedAt();
		result.lastUsedAt = value.lastUsedAt();
		result.createdAt = value.createdAt();
		return result;
	}

	DatasetCacheView domain() {
		return new DatasetCacheView(this.id, this.ownerType, this.ownerId, this.measuredBytes, this.verifiedAt,
				this.lastUsedAt, this.createdAt);
	}

}
