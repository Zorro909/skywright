package de.zorro909.skywright.backend.datasetpublication;

import java.time.Instant;
import java.util.UUID;

record DatasetPublicationView(UUID publicationId, DatasetPublicationState state, UUID datasetId, UUID definitionId,
		UUID copyId, UUID targetStorageId, Long expectedDatasetRevision,
		PreferredDefinitionDecision preferredDefinitionDecision, String versionLabel, String formatIdentity,
		String manifestIdentity, String contentFingerprint, long objectCount, long byteCount, String payloadLocation,
		String operationLocation, long uploadedObjectCount, long uploadedByteCount, long verifiedObjectCount,
		long verifiedByteCount, UUID preferredDefinitionId, boolean preferredDefinitionChanged, boolean retryable,
		String failureCode, String failureDetail, String unavailableSource, String retryGuidance, Instant createdAt,
		Instant updatedAt, Instant verifiedAt, Instant completedAt, long verificationWorkerPid) {
}
