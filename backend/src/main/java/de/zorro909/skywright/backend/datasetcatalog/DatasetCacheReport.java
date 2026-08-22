package de.zorro909.skywright.backend.datasetcatalog;

import java.time.Instant;
import java.util.UUID;

public record DatasetCacheReport(UUID cacheId, DatasetCacheOwnerType ownerType, String ownerId, long measuredBytes,
		Instant verifiedAt, Instant lastUsedAt) {
}
