package de.zorro909.skywright.backend.datasetcatalog;

import java.time.Instant;
import java.util.UUID;

public record DatasetCacheView(UUID id, DatasetCacheOwnerType ownerType, String ownerId, long measuredBytes,
		Instant verifiedAt, Instant lastUsedAt, Instant createdAt) {
}
