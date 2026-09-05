package de.zorro909.skywright.backend.orchestration;

import java.util.concurrent.CompletionStage;

public interface Orchestrator extends AutoCloseable {

	CompletionStage<OrchestratorResult<OrchestratorOperation>> submit(OrchestratorTaskSpecification task);

	default CompletionStage<OrchestratorResult<OrchestratorOperation>> submit(OrchestratorTaskSpecification task,
			de.zorro909.skywright.backend.credential.TrainingCredentials credentials) {
		throw new UnsupportedOperationException("Credential projection transport is unavailable");
	}

	CompletionStage<OrchestratorResult<OrchestratorOperation>> observe(StatusRequest request);

	CompletionStage<OrchestratorResult<OrchestratorOperation>> control(ControlRequest request);

	CompletionStage<OrchestratorResult<OrchestratorOperation>> cleanup(CleanupRequest request);

	CompletionStage<OrchestratorResult<OperationOutcome>> complete(OrchestratorOperation operation);

	SkyPilotAvailability availability();

	CompletionStage<SkyPilotAvailability> refreshAvailability();

	@Override
	void close();

}
