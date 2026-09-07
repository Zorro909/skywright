package de.zorro909.skywright.backend.runsubmission;

import de.zorro909.skywright.backend.trainingproject.ReferencedProjectArtifact;

import java.util.UUID;
import de.zorro909.skywright.backend.credential.TrainingCredentials;
import de.zorro909.skywright.backend.orchestration.OrchestratorTaskSpecification;
import de.zorro909.skywright.backend.rundefinition.RunDefinition;

/** Resolution and Run-owned lease/projection writes join the acceptance transaction. */
public interface LocalRunAdmission {

	Prepared prepare(UUID runId, LocalRunRequest request);

	record Prepared(RunDefinition definition, OrchestratorTaskSpecification task, TrainingCredentials credentials,
			java.util.Set<ReferencedProjectArtifact> artifacts,
			de.zorro909.skywright.backend.credential.RuntimePullProjection runtimePull) implements AutoCloseable {
		public Prepared {
			artifacts = java.util.Set.copyOf(artifacts);
		}

		public Prepared(RunDefinition definition, OrchestratorTaskSpecification task, TrainingCredentials credentials,
				java.util.Set<ReferencedProjectArtifact> artifacts) {
			this(definition, task, credentials, artifacts, null);
		}

		public Prepared(RunDefinition definition, OrchestratorTaskSpecification task, TrainingCredentials credentials) {
			this(definition, task, credentials, java.util.Set.of());
		}

		public void close() {
			if (credentials != null)
				credentials.close();
			if (runtimePull != null)
				runtimePull.close();
		}
	}

}
