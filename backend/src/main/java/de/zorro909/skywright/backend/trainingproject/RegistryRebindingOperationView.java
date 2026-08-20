package de.zorro909.skywright.backend.trainingproject;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

record RegistryRebindingOperationView(UUID id, UUID projectId, long candidateBindingRevision, String state,
		int attempts, List<RebindingArtifact> artifacts, List<String> failureCodes, Instant startedAt,
		Instant completedAt) {

	RegistryRebindingOperationView {
		artifacts = List.copyOf(artifacts);
		failureCodes = List.copyOf(failureCodes);
	}

}
