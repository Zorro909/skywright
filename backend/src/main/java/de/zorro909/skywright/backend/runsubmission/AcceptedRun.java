package de.zorro909.skywright.backend.runsubmission;

import de.zorro909.skywright.backend.trainingproject.ReferencedProjectArtifact;

import java.time.Instant;
import java.util.UUID;
import de.zorro909.skywright.backend.rundefinition.RunDefinition;
import de.zorro909.skywright.backend.orchestration.OrchestratorTaskSpecification;

public record AcceptedRun(UUID runId, UUID submissionId, String requestDigest, Instant acceptedAt,
		RunDefinition definition, OrchestratorTaskSpecification task,
		java.util.Set<ReferencedProjectArtifact> artifacts) {
	public AcceptedRun {
		artifacts = java.util.Set.copyOf(artifacts);
	}
}
