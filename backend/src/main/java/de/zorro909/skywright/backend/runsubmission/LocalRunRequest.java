package de.zorro909.skywright.backend.runsubmission;

import java.util.Map;
import java.util.UUID;

public record LocalRunRequest(UUID submissionId, UUID trainingProjectId, String manifestArtifactDigest,
		UUID datasetDefinitionId, UUID preferredDatasetCopyId, UUID executionStorageId, String target, int gpuCount,
		Map<String, Object> configuration, Integer maximumRecoveryDebt,
		@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) CheckpointSeed checkpointSeed) {
	public record CheckpointSeed(UUID predecessorRunId, String checkpointReference) {
		public CheckpointSeed {
			java.util.Objects.requireNonNull(predecessorRunId);
			de.zorro909.skywright.backend.runstore.CheckpointReference.parse(checkpointReference);
		}
	}

	public LocalRunRequest(UUID submissionId, UUID trainingProjectId, String manifestArtifactDigest,
			UUID datasetDefinitionId, UUID preferredDatasetCopyId, UUID executionStorageId, String target, int gpuCount,
			Map<String, Object> configuration, Integer maximumRecoveryDebt) {
		this(submissionId, trainingProjectId, manifestArtifactDigest, datasetDefinitionId, preferredDatasetCopyId,
				executionStorageId, target, gpuCount, configuration, maximumRecoveryDebt, null);
	}

	public LocalRunRequest {
		configuration = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(configuration));
	}
}
