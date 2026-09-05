package de.zorro909.skywright.backend.orchestration;

interface SkyPilotClient extends AutoCloseable {

	String version();

	void probe() throws Exception;

	OrchestratorOperation submit(OrchestratorTaskSpecification task) throws Exception;

	default OrchestratorOperation submit(OrchestratorTaskSpecification task,
			de.zorro909.skywright.backend.credential.TrainingCredentials credentials) throws Exception {
		throw new UnsupportedOperationException("Credential projection transport is unavailable");
	}

	OrchestratorOperation observe(StatusRequest request) throws Exception;

	OrchestratorOperation control(ControlRequest request) throws Exception;

	OrchestratorOperation cleanup(CleanupRequest request) throws Exception;

	OperationOutcome complete(OrchestratorOperation operation) throws Exception;

	@Override
	void close();

}
