package de.zorro909.skywright.backend.datasetpublication;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

record DatasetPublicationOpenCredentialProjection(UUID projectionId, UUID publicationId, Long workerPid,
		Instant workerStartedAt, Path jobDirectory) {
}
