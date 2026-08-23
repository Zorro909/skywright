package de.zorro909.skywright.backend.datasetpublication;

import java.time.Instant;
import java.util.UUID;

record DatasetPublicationView(UUID publicationId, DatasetPublicationState state, UUID datasetId, UUID definitionId,
		UUID copyId, UUID targetStorageId, String versionLabel, String formatIdentity, String manifestIdentity,
		String contentFingerprint, long objectCount, long byteCount, String payloadLocation, String operationLocation,
		long verifiedObjectCount, long verifiedByteCount, UUID preferredDefinitionId,
		boolean preferredDefinitionChanged, boolean retryable, String failureCode, Instant createdAt,
		Instant verifiedAt, Instant completedAt, long verificationWorkerPid) {
}
