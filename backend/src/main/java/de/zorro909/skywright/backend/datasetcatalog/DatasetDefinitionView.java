package de.zorro909.skywright.backend.datasetcatalog;

import java.time.Instant;
import java.util.UUID;

public record DatasetDefinitionView(UUID datasetId, UUID definitionId, String versionLabel, String formatIdentity,
		String contentFingerprint, String manifestIdentity, Instant createdAt) {
}
