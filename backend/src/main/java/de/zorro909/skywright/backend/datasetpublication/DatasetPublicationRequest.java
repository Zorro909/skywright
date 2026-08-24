package de.zorro909.skywright.backend.datasetpublication;

import java.util.UUID;

record DatasetPublicationRequest(UUID targetStorageId, UUID datasetId, Long expectedDatasetRevision,
		PreferredDefinitionDecision preferredDefinitionDecision, String versionLabel, String formatIdentity,
		String manifestIdentity, String contentFingerprint, long objectCount, long byteCount) {
}
