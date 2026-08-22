package de.zorro909.skywright.backend.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("real-service")
final class GraalPySkyPilotClientIT {

	private static SkyPilotApiServerFixture apiServer;

	@BeforeAll
	static void startApiServer() throws Exception {
		apiServer = SkyPilotApiServerFixture.start();
	}

	@AfterAll
	static void stopApiServer() throws Exception {
		if (apiServer != null) {
			apiServer.close();
		}
	}

	@Test
	void oneLockedGraalPyContextExercisesThePortAndCancelsHeldNativeWorkOnClose() throws Exception {
		var client = client();
		client.probe();
		apiServer.stop();
		try {
			client.observe(new StatusRequest(List.of("missing-job")));
			throw new AssertionError("Reachability loss was not reported");
		}
		catch (SkyPilotClientFailure failure) {
			assertThat(failure.causeCategory()).isEqualTo(BridgeFailure.FailureCause.REACHABILITY);
		}
		apiServer.restart();
		client.probe();

		var submittedTask = task();
		var submission = client.submit(submittedTask);
		var duplicateSubmission = client.submit(submittedTask);
		var status = client.observe(new StatusRequest(List.of("missing-job")));
		var control = client.control(new ControlRequest("missing-job", ControlRequest.Action.CANCEL));
		var cleanup = client.cleanup(new CleanupRequest("missing-cluster"));

		assertThat(submission.kind()).isEqualTo(OperationKind.SUBMISSION);
		assertThat(duplicateSubmission).isEqualTo(submission);
		assertThat(control.kind()).isEqualTo(OperationKind.CONTROL);
		assertThat(cleanup.kind()).isEqualTo(OperationKind.CLEANUP);
		assertThat(client.complete(status))
			.isEqualTo(new OperationOutcome.Failed("ClusterNotUpError", "SkyPilot target is unavailable"));

		var orchestrator = new SkyPilotOrchestrator(client, new SkyPilotBridgeSettings(2, 1, Duration.ofMillis(100)));
		orchestrator.refreshAvailability().toCompletableFuture().get(10, TimeUnit.SECONDS);
		var operation = orchestrator.submit(task()).toCompletableFuture().get(10, TimeUnit.SECONDS).value();
		var held = orchestrator.complete(operation);

		orchestrator.close();

		assertThat(held.toCompletableFuture()).isCompleted();
	}

	private static GraalPySkyPilotClient client() {
		return new GraalPySkyPilotClient(Path.of(System.getProperty("graalpy.external.directory")),
				apiServer.endpoint());
	}

	private static OrchestratorTaskSpecification task() {
		return new OrchestratorTaskSpecification("skywright-bridge-test-" + UUID.randomUUID(), null, "echo bridge-test",
				List.of(new OrchestratorTaskSpecification.Resources("kubernetes", "2", "4", null, null, false)),
				Map.of());
	}

}
