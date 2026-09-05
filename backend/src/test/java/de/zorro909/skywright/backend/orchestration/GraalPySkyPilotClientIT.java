package de.zorro909.skywright.backend.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import de.zorro909.skywright.backend.pricing.SkyPilotCatalogueQuery;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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
	void oneLockedGraalPyContextExercisesCatalogueAndOrchestrationThroughTheApiServer() throws Exception {
		var client = client();
		client.probe();
		assertCredentialTask(client);
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
		Instant observedAt = Instant.parse("2030-01-15T12:00:00Z");
		assertThat(client
			.price(new SkyPilotCatalogueQuery("aws", "us-east-1", "p5.48xlarge", "H100", 8, false, observedAt)))
			.isEmpty();

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

	private static void assertCredentialTask(GraalPySkyPilotClient client) throws Exception {
		// Inspect the one production context; native SkyPilot dependencies cannot be
		// initialized in an extra interpreter just for this assertion.
		var field = GraalPySkyPilotClient.class.getDeclaredField("context");
		field.setAccessible(true);
		var context = (org.graalvm.polyglot.Context) field.get(client);
		context.eval("python", """
				import sky
				specification = {'name': 'projection-fixture', 'run': 'env', 'environment': {},
				    'resources': [{'infrastructure': 'kubernetes', 'cpus': '2', 'memory': '4', 'useSpot': False}],
				    'runtimePullSecret': 'skywright-pull-00000000-0000-0000-0000-000000000001'}
				secrets = {'SKYWRIGHT_DATASET_ACCESS_KEY_ID': 'reader',
				    'SKYWRIGHT_DATASET_SECRET_ACCESS_KEY': 'reader-secret',
				    'SKYWRIGHT_RUN_STORE_ACCESS_KEY_ID': 'writer',
				    'SKYWRIGHT_RUN_STORE_SECRET_ACCESS_KEY': 'writer-secret'}
				task = _task(specification, secrets)
				recovered = sky.Task.from_yaml_config(task.to_yaml_config())
				assert recovered.secrets['SKYWRIGHT_DATASET_ACCESS_KEY_ID'].get_secret_value() == 'reader'
				assert recovered.envs == {}
				assert 'writer-secret' not in str(task.to_yaml_config(use_user_specified_yaml=True))
				assert 'imagePullSecrets' in str(task.to_yaml_config())
				assert 'ghcr.io' not in str(task.to_yaml_config())
				try:
				    _task(specification, {'VAULT_TOKEN': 'forbidden'})
				    raise AssertionError('Vault token admitted')
				except ValueError:
				    pass
				""");
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
