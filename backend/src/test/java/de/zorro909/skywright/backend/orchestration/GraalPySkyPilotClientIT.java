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
	void oneNativeGraalPyContextExercisesCatalogueAndOrchestrationThroughTheApiServer() throws Exception {
		var client = client();
		client.probe();
		var orchestrator = new SkyPilotOrchestrator(client, new SkyPilotBridgeSettings(2, 1, Duration.ofMillis(100)));
		orchestrator.refreshAvailability().toCompletableFuture().get(10, TimeUnit.SECONDS);
		assertCredentialTask(client);
		assertSourceEvidenceDecoding(client, orchestrator);
		assertConcurrentAuthorization(client);
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

		orchestrator.refreshAvailability().toCompletableFuture().get(10, TimeUnit.SECONDS);
		var operation = orchestrator.submit(task()).toCompletableFuture().get(10, TimeUnit.SECONDS).value();
		var held = orchestrator.complete(operation);

		orchestrator.close();

		assertThat(held.toCompletableFuture()).isCompleted();
	}

	private static void assertSourceEvidenceDecoding(GraalPySkyPilotClient client, SkyPilotOrchestrator orchestrator)
			throws Exception {
		var field = GraalPySkyPilotClient.class.getDeclaredField("context");
		field.setAccessible(true);
		var context = (org.graalvm.polyglot.Context) field.get(client);
		context.eval("python",
				"""
						from sky.schemas.api.responses import ManagedJobRecord
						from sky.jobs.state import ManagedJobStatus
						_original_stream = sky.stream_and_get
						_source_records = [ManagedJobRecord(job_id=42, job_name='skywright-123e4567-e89b-12d3-a456-426614174000',
						    status=ManagedJobStatus.FAILED, recovery_count=2, task_id=0,
						    submitted_at=100., start_at=110., end_at=150., last_recovered_at=130.,
						    run_timestamp='source-generation', cloud='kubernetes', region='local',
						    cluster_resources='MI300X:1', failure_reason='application failed')]
						sky.stream_and_get = lambda request: (_source_records, 1, {}, 1)
						""");
		try {
			var outcome = (OperationOutcome.Observed) client.complete(new OrchestratorOperation(
					"{\"request_id\":\"synthetic-source-result\",\"names\":[\"skywright-123e4567-e89b-12d3-a456-426614174000\"]}",
					OperationKind.STATUS));
			assertThat(outcome.complete()).isTrue();
			var job = outcome.jobs().getFirst();
			assertThat(job.jobId()).isEqualTo(42);
			assertThat(job.endedAt()).isEqualTo(150.0);
			assertThat(job.recoveryCount()).isEqualTo(2);
			assertThat(job.status()).isEqualTo("FAILED");
			assertThat(job.cloud()).isEqualTo("kubernetes");
			var runId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
			var facts = new java.util.ArrayList<RetainedSkyPilotFact>();
			var adapter = new RunJobAdapter(orchestrator, (run, fingerprint) -> java.util.concurrent.CompletableFuture
				.completedFuture(LaunchDispatchGate.Decision.ALREADY_DISPATCHED), batch -> {
					facts.addAll(batch);
					return java.util.concurrent.CompletableFuture.completedFuture(null);
				}, java.time.Clock.systemUTC());
			var observed = adapter.reconcile(runId).toCompletableFuture().get(10, TimeUnit.SECONDS);
			assertThat(observed.availability()).isEqualTo(RunJobAdapter.SourceAvailability.LIVE);
			var lifecycle = new de.zorro909.skywright.backend.runlifecycle.RunLifecycleDerivation()
				.derive(new de.zorro909.skywright.backend.runlifecycle.RunLifecycleDerivation.Evidence(
						observed.availability(), observed.liveJobs(), observed.retainedFacts(), facts,
						new de.zorro909.skywright.backend.runstore.RunProcessEvidence(List.of(), null, 0, null),
						List.of(), Instant.now(), observed.evidenceGaps()));
			assertThat(lifecycle.state()).isEqualTo(de.zorro909.skywright.backend.runlifecycle.RunLifecycle.FAILED);
			assertThat(lifecycle.cause()).isNull();
			assertThat(lifecycle.terminalLatched()).isTrue();

			context.eval("python", "sky.stream_and_get = lambda request: (_source_records, 1001, {}, 1001)");
			assertThat(((OperationOutcome.Observed) client
				.complete(new OrchestratorOperation("{\"request_id\":\"partial\",\"names\":[]}", OperationKind.STATUS)))
				.complete()).isFalse();
		}
		finally {
			context.eval("python", "sky.stream_and_get = _original_stream");
		}
		// Expired request identifiers are rejected by the actual API server; the adapter
		// must rediscover by Run identity instead of replaying this operation.
		assertThat(
				client.complete(new OrchestratorOperation("{\"request_id\":\"00000000-0000-0000-0000-000000000999\"}",
						OperationKind.SUBMISSION)))
			.isInstanceOf(OperationOutcome.Failed.class);
	}

	private static void assertCredentialTask(GraalPySkyPilotClient client) throws Exception {
		// Inspect the one production context; native SkyPilot dependencies cannot be
		// initialized in an extra interpreter just for this assertion.
		var field = GraalPySkyPilotClient.class.getDeclaredField("context");
		field.setAccessible(true);
		var context = (org.graalvm.polyglot.Context) field.get(client);
		context.eval("python",
				"""
						import sky
						specification = {'name': 'projection-fixture', 'run': 'env', 'environment': {},
						    'resources': [{'infrastructure': 'kubernetes', 'cpus': '2', 'memory': '4', 'useSpot': False,
						        'jobRecovery': {'maxRestartsOnErrors': 0, 'recoverOnExitCodes': [75]}}],
						    'runtimePullSecret': 'skywright-pull-00000000-0000-0000-0000-000000000001',
						    'runtimePullNamespace': 'training'}
						secrets = {'SKYWRIGHT_DATASET_ACCESS_KEY_ID': 'reader',
						    'SKYWRIGHT_DATASET_SECRET_ACCESS_KEY': 'reader-secret',
						    'SKYWRIGHT_RUN_STORE_ACCESS_KEY_ID': 'writer',
						    'SKYWRIGHT_RUN_STORE_SECRET_ACCESS_KEY': 'writer-secret'}
						task = _task(specification, secrets)
						recovered = sky.Task.from_yaml_config(task.to_yaml_config())
						assert recovered.secrets['SKYWRIGHT_DATASET_ACCESS_KEY_ID'].get_secret_value() == 'reader'
						assert recovered.envs == {}
						assert next(iter(recovered.resources)).job_recovery['max_restarts_on_errors'] == 0
						assert next(iter(recovered.resources)).job_recovery['recover_on_exit_codes'] == [75]
						assert 'writer-secret' not in str(task.to_yaml_config(use_user_specified_yaml=True))
						assert 'imagePullSecrets' in str(task.to_yaml_config())
						assert next(iter(recovered.resources)).cluster_config_overrides['kubernetes']['namespace'] == 'training'
						assert 'ghcr.io' not in str(task.to_yaml_config())
						specification['name'] = 'skywright-00000000-0000-0000-0000-000000000001'
						specification['resources'][0]['infrastructure'] = 'kubernetes/local'
						specification['environment']['SKYWRIGHT_WRITER_AUTHORITY_SOCKET'] = '/run/skywright-writer/authority.sock'
						qualified = sky.Task.from_yaml_config(_task(specification, secrets).to_yaml_config())
						resource = next(iter(qualified.resources))
						assert resource.job_recovery['strategy'] == 'EAGER_NEXT_REGION'
						pod = resource.cluster_config_overrides['kubernetes']['pod_config']
						assert pod['metadata']['labels']['skywright.io/run-id'] == '00000000-0000-0000-0000-000000000001'
						assert pod['spec']['imagePullSecrets'][0]['name'] == specification['runtimePullSecret']
						assert pod['spec']['hostPID'] is False and pod['spec']['shareProcessNamespace'] is False
						assert pod['spec']['containers'][0]['volumeMounts'][0]['readOnly'] is True
						assert qualified.envs['SKYWRIGHT_WRITER_AUTHORITY_SOCKET'] == '/run/skywright-writer/authority.sock'
						assert resource.cluster_config_overrides['kubernetes']['namespace'] == 'training'
						try:
						    _task(specification, {'VAULT_TOKEN': 'forbidden'})
						    raise AssertionError('Vault token admitted')
						except ValueError:
						    pass
						""");
	}

	private static void assertConcurrentAuthorization(GraalPySkyPilotClient client) throws Exception {
		var field = GraalPySkyPilotClient.class.getDeclaredField("context");
		field.setAccessible(true);
		var context = (org.graalvm.polyglot.Context) field.get(client);
		context.eval("python", """
				from sky.client import service_account_auth
				_authorization_barrier = threading.Barrier(2)
				def _qualification_authorization():
				    before = service_account_auth.get_service_account_headers()
				    _authorization_barrier.wait(timeout=3)
				    after = service_account_auth.get_service_account_headers()
				    assert before == after
				    return json.dumps(after)
				""");
		var call = context.getBindings("python").getMember("bridge_call");
		try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
			var first = workers.submit(() -> call.execute("_qualification_authorization", "sky_first").asString());
			var second = workers.submit(() -> call.execute("_qualification_authorization", "sky_second").asString());
			assertThat(first.get(5, TimeUnit.SECONDS)).contains("Bearer sky_first").doesNotContain("sky_second");
			assertThat(second.get(5, TimeUnit.SECONDS)).contains("Bearer sky_second").doesNotContain("sky_first");
		}
		context.eval("python",
				"assert os.environ.get('SKYPILOT_SERVICE_ACCOUNT_TOKEN') not in ('sky_first', 'sky_second')");
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
