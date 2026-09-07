package de.zorro909.skywright.backend.runsubmission;

import java.util.Map;
import java.util.UUID;

public record LocalRunRequest(UUID submissionId, UUID trainingProjectId, String manifestArtifactDigest,
		UUID datasetDefinitionId, UUID preferredDatasetCopyId, UUID executionStorageId, String target, int gpuCount,
		Map<String, Object> configuration, Integer maximumRecoveryDebt) {
	public LocalRunRequest {
		configuration = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(configuration));
	}
}
