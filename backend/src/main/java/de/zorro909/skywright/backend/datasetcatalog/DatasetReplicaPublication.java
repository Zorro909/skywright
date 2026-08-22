package de.zorro909.skywright.backend.datasetcatalog;

import java.time.Instant;
import java.util.UUID;

public record DatasetReplicaPublication(UUID copyId, UUID targetStorageId, String location, long verifiedBytes,
		Instant verifiedAt) {
}
