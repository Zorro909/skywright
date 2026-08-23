package de.zorro909.skywright.backend.datasetpublication;

import java.time.Instant;
import java.util.UUID;

record DatasetLineageView(UUID datasetId, long revision, UUID preferredDefinitionId, Instant createdAt) {
}
