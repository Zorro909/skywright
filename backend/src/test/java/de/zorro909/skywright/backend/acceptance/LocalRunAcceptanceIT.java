package de.zorro909.skywright.backend.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import de.zorro909.skywright.backend.orchestration.*;
import de.zorro909.skywright.backend.rundefinition.RunDefinition;
import de.zorro909.skywright.backend.runsubmission.LocalRunAdmission;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.json.JsonMapper;

@Tag("real-service")
class LocalRunAcceptanceIT {

	private static final Source SOURCE = new Source();

	private static final AtomicInteger ADMISSIONS = new AtomicInteger();

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void atomicAcceptanceSurvivesLostResponsesConcurrentRequestsAndBackendRestart() throws Exception {
		SOURCE.launches.set(0);
		SOURCE.available = false;
		SOURCE.jobs = List.of();
		ADMISSIONS.set(0);
		try (var backend = BackendFixture.startWith(Boundaries.class, "local-run-integration")) {
			String request = request(UUID.randomUUID());
			var unavailable = backend.post("/api/v1/runs", request);
			assertThat(unavailable.statusCode()).as(unavailable.body()).isEqualTo(503);
			assertThat(ADMISSIONS).hasValue(0);
			SOURCE.available = true;
			try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
				var one = workers.submit(() -> backend.post("/api/v1/runs", request));
				var two = workers.submit(() -> backend.post("/api/v1/runs", request));
				var first = one.get();
				var second = two.get();
				assertThat(first.statusCode()).as(first.body()).isEqualTo(202);
				assertThat(second.statusCode()).as(second.body()).isEqualTo(202);
				String runId = JSON.readTree(first.body()).path("runId").asText();
				assertThat(JSON.readTree(second.body()).path("runId").asText()).isEqualTo(runId);
				assertThat(SOURCE.launches).hasValue(1);
				assertThat(first.body()).contains("\"acceptedIntent\":\"submit\"", "\"handoff\":\"uncertain\"")
					.doesNotContain("request_id", "task_json", "credentials");
				var definition = JSON.readTree(first.body()).path("definition");
				int resolved = ADMISSIONS.get();
				SOURCE.available = false;
				var replay = backend.post("/api/v1/runs", request);
				assertThat(replay.statusCode()).isEqualTo(202);
				assertThat(replay.body()).contains("\"sourceAvailability\":\"unavailable\"");
				assertThat(ADMISSIONS).hasValue(resolved);
				assertThat(
						backend.post("/api/v1/runs", request.replace("\"gpuCount\":1", "\"gpuCount\":2")).statusCode())
					.isEqualTo(409);
				backend.restart();
				SOURCE.available = true;
				for (int i = 0; i < 3; i++) {
					var delayed = backend.post("/api/v1/runs", request);
					assertThat(delayed.statusCode()).isEqualTo(202);
					assertThat(delayed.body()).contains("\"sourceAvailability\":\"missing\"");
				}
				assertThat(SOURCE.launches).hasValue(1);
				SOURCE.jobs = List
					.of(new OperationOutcome.ManagedJobStatus(42L, "skywright-" + runId, "SUCCEEDED", 0, 0, 100., 110.,
							150., null, "source-generation", "kubernetes", "local", null, "MI300X:1", null, "worker"));
				var observed = backend.get("/api/v1/runs/" + runId);
				assertThat(observed.statusCode()).as(observed.body()).isEqualTo(200);
				assertThat(observed.body()).contains("\"handoff\":\"source-observed\"");
				assertThat(JSON.readTree(observed.body()).path("definition")).isEqualTo(definition);
				var sink = backend.bean(de.zorro909.skywright.backend.runsubmission.RunAcceptanceStore.class);
				for (var fact : List.of(
						new RetainedSkyPilotFact(UUID.fromString(runId), RetainedSkyPilotFact.Kind.TERMINATION,
								"ordering-test", Map.of("status", "A"), java.time.Instant.ofEpochSecond(100)),
						new RetainedSkyPilotFact(UUID.fromString(runId), RetainedSkyPilotFact.Kind.TERMINATION,
								"ordering-test", Map.of("status", "A"), java.time.Instant.ofEpochSecond(200)),
						new RetainedSkyPilotFact(UUID.fromString(runId), RetainedSkyPilotFact.Kind.TERMINATION,
								"ordering-test", Map.of("status", "B"), java.time.Instant.ofEpochSecond(150))))
					sink.append(List.of(fact)).toCompletableFuture().join();

				try (var connection = backend.bean(DataSource.class).getConnection();
						var statement = connection.createStatement()) {
					try (var rows = statement.executeQuery("select count(*) from skywright.run_record")) {
						rows.next();
						assertThat(rows.getLong(1)).isEqualTo(1);
					}
					try (var rows = statement.executeQuery("select count(*) from skywright.run_launch_claim")) {
						rows.next();
						assertThat(rows.getLong(1)).isEqualTo(1);
					}
					try (var rows = statement.executeQuery("select count(*) from skywright.retained_skypilot_fact")) {
						rows.next();
						assertThat(rows.getLong(1)).isGreaterThan(0);
					}
					try (var rows = statement.executeQuery(
							"select f.payload_json from skywright.retained_skypilot_fact f join skywright.skypilot_fact_observation o on o.fact_id=f.id where f.source_event_identity='ordering-test' order by o.observed_at desc limit 1")) {
						rows.next();
						assertThat(rows.getString(1)).contains("A");
					}
					try (var rows = statement.executeQuery(
							"select count(*) from skywright.retained_skypilot_fact where source_event_identity='ordering-test'")) {
						rows.next();
						assertThat(rows.getLong(1)).isEqualTo(2);
					}

					assertThatThrownBy(
							() -> statement.executeUpdate("update skywright.run_record set definition_json='{}'"))
						.isInstanceOf(java.sql.SQLException.class);
				}
			}
		}
	}

	@Test
	void acknowledgementAfterHttpTimeoutStillRetainsOperationFailure() throws Exception {
		SOURCE.available = true;
		SOURCE.jobs = List.of();
		SOURCE.launches.set(0);
		SOURCE.delayedSubmission = new CompletableFuture<>();
		try (var backend = BackendFixture.startWith(Boundaries.class, "local-run-integration")) {
			String request = request(UUID.randomUUID());
			var response = backend.post("/api/v1/runs", request);
			assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
			assertThat(response.body()).contains("\"handoff\":\"uncertain\"");
			SOURCE.delayedSubmission
				.complete(OrchestratorResult.accepted(new OrchestratorOperation("late", OperationKind.SUBMISSION)));
			try (var connection = backend.bean(DataSource.class).getConnection();
					var statement = connection.createStatement();
					var rows = statement.executeQuery(
							"select count(*) from skywright.retained_skypilot_fact where fact_kind='SUBMISSION_OPERATION_FAILURE'")) {
				rows.next();
				assertThat(rows.getLong(1)).isEqualTo(1);
			}
			assertThat(backend.post("/api/v1/runs", request).statusCode()).isEqualTo(202);
			assertThat(SOURCE.launches).hasValue(1);
		}
		finally {
			SOURCE.delayedSubmission = null;
		}
	}

	@Test
	void failedAdmissionRollsBackAndCrashAfterCommitDoesNotScheduleAnotherLaunch() throws Exception {
		SOURCE.launches.set(0);
		SOURCE.available = true;
		SOURCE.jobs = List.of();
		try (var backend = BackendFixture.startWith(Boundaries.class, "local-run-integration")) {
			var submission = UUID.randomUUID();
			FAIL_PREPARE = true;
			try {
				var failed = backend.post("/api/v1/runs", request(submission));
				assertThat(failed.statusCode()).as(failed.body()).isEqualTo(503);
				try (var connection = backend.bean(DataSource.class).getConnection();
						var statement = connection.createStatement()) {
					try (var rows = statement.executeQuery("select count(*) from skywright.run_record")) {
						rows.next();
						assertThat(rows.getLong(1)).isZero();
					}
					try (var rows = statement
						.executeQuery("select count(*) from skywright.local_credential_projection")) {
						rows.next();
						assertThat(rows.getLong(1)).isZero();
					}
				}
			}
			finally {
				FAIL_PREPARE = false;
			}
			var input = new de.zorro909.skywright.backend.runsubmission.LocalRunRequest(submission,
					UUID.fromString("00000000-0000-0000-0000-000000000001"), "sha256:" + "9".repeat(64),
					UUID.fromString("00000000-0000-0000-0000-000000000002"), null, null, "local/amd", 1, Map.of(),
					null);
			var accepted = de.zorro909.skywright.backend.runsubmission.AcceptedRunCrashFixture.commitWithoutDispatch(
					backend.bean(de.zorro909.skywright.backend.runsubmission.RunAcceptanceStore.class),
					backend.bean(LocalRunAdmission.class), input);
			backend.restart();
			assertThat(backend.post("/api/v1/runs", request(submission)).body()).contains(accepted.runId().toString(),
					"\"handoff\":\"uncertain\"");
			assertThat(SOURCE.launches).hasValue(0);
			var gate = backend.bean(de.zorro909.skywright.backend.runsubmission.RunAcceptanceStore.class);
			assertThat(gate.claim(accepted.runId(), "sha256:" + "0".repeat(64)).toCompletableFuture().join())
				.isEqualTo(LaunchDispatchGate.Decision.DEFINITION_CONFLICT);
			try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
				var barrier = new java.util.concurrent.CyclicBarrier(2);
				java.util.concurrent.Callable<LaunchDispatchGate.Decision> claim = () -> {
					barrier.await();
					return gate.claim(accepted.runId(), RunJobAdapter.taskFingerprint(accepted.task()))
						.toCompletableFuture()
						.join();
				};
				var first = workers.submit(claim);
				var second = workers.submit(claim);
				assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(
						LaunchDispatchGate.Decision.FIRST_DISPATCH, LaunchDispatchGate.Decision.ALREADY_DISPATCHED);
			}
			backend.restart();
			assertThat(backend.post("/api/v1/runs", request(submission)).body()).contains("\"handoff\":\"uncertain\"");
			assertThat(SOURCE.launches).hasValue(0);
		}
	}

	private static volatile boolean FAIL_PREPARE;

	private static String request(UUID submission) {
		return "{\"submissionId\":\"" + submission
				+ "\",\"trainingProjectId\":\"00000000-0000-0000-0000-000000000001\","
				+ "\"manifestArtifactDigest\":\"sha256:" + "9".repeat(64)
				+ "\",\"datasetDefinitionId\":\"00000000-0000-0000-0000-000000000002\","
				+ "\"target\":\"local/amd\",\"gpuCount\":1,\"configuration\":{}}";
	}

	@Configuration(proxyBeanMethods = false)
	@org.springframework.context.annotation.Profile("local-run-integration")
	static class Boundaries {

		@Bean
		@Primary
		Orchestrator acceptanceSource() {
			return SOURCE;
		}

		@Bean
		@Primary
		LocalRunAdmission fixtureAdmission(de.zorro909.skywright.backend.credential.LocalProjectionFacts facts,
				de.zorro909.skywright.backend.targetstorage.TargetStorageRegistry storages) {
			return (run, request) -> {
				ADMISSIONS.incrementAndGet();
				facts.begin(run, "dataset",
						new de.zorro909.skywright.backend.credential.CredentialBinding(
								UUID.fromString("00000000-0000-0000-0000-000000000099"), 1, "fixtures/dataset",
								de.zorro909.skywright.backend.credential.CredentialBinding.Kind.S3, "dataset-store",
								"training-process", "reader", "dataset", "read-only",
								java.time.Instant.parse("2026-01-01T00:00:00Z"), null, true));
				if (FAIL_PREPARE)
					throw new IllegalStateException("crash before acceptance");
				try {
					var document = JSON.readTree(Files.readString(Path.of(System.getProperty("repository.root"),
							"sdk/tests/fixtures/managed-runtime/definition.json")));
					var storageId = de.zorro909.skywright.backend.targetstorage.RunStoreReferenceFixture
						.register(storages, run);
					((tools.jackson.databind.node.ObjectNode) document.at("/storage/execution")).put("storageId",
							storageId.toString());
					var definition = RunDefinition.decode(document.toString());
					var task = new OrchestratorTaskSpecification("skywright-" + run, null, "train",
							List.of(new OrchestratorTaskSpecification.Resources("kubernetes/local", "8", "32",
									"MI300X:1", "docker:fixture", false)),
							Map.of());
					return new LocalRunAdmission.Prepared(definition, task, null);
				}
				catch (java.io.IOException error) {
					throw new IllegalStateException(error);
				}
			};
		}

	}

	static class Source implements Orchestrator {

		volatile CompletableFuture<OrchestratorResult<OrchestratorOperation>> delayedSubmission;

		final AtomicInteger launches = new AtomicInteger();

		volatile boolean available;

		volatile List<OperationOutcome.ManagedJobStatus> jobs = List.of();

		public CompletionStage<OrchestratorResult<OrchestratorOperation>> submit(OrchestratorTaskSpecification task) {
			launches.incrementAndGet();
			if (delayedSubmission != null)
				return delayedSubmission;
			return CompletableFuture.failedFuture(new IllegalStateException("response lost after remote acceptance"));
		}

		public CompletionStage<OrchestratorResult<OrchestratorOperation>> observe(StatusRequest request) {
			return CompletableFuture.completedFuture(
					available ? OrchestratorResult.accepted(new OrchestratorOperation("fresh", OperationKind.STATUS))
							: OrchestratorResult.failure(
									BridgeFailure.unavailable(BridgeFailure.FailureCause.REACHABILITY, "offline")));
		}

		public CompletionStage<OrchestratorResult<OrchestratorOperation>> control(ControlRequest request) {
			throw new UnsupportedOperationException();
		}

		public CompletionStage<OrchestratorResult<OrchestratorOperation>> cleanup(CleanupRequest request) {
			throw new UnsupportedOperationException();
		}

		public CompletionStage<OrchestratorResult<OperationOutcome>> complete(OrchestratorOperation operation) {
			if (operation.kind() == OperationKind.SUBMISSION)
				return CompletableFuture.completedFuture(OrchestratorResult
					.accepted(new OperationOutcome.Failed("ResourcesUnavailableError", "capacity unavailable")));
			return CompletableFuture.completedFuture(OrchestratorResult.accepted(new OperationOutcome.Observed(jobs)));
		}

		public SkyPilotAvailability availability() {
			return available ? SkyPilotAvailability.healthy() : SkyPilotAvailability
				.unavailable(BridgeFailure.unavailable(BridgeFailure.FailureCause.REACHABILITY, "offline"));
		}

		public CompletionStage<SkyPilotAvailability> refreshAvailability() {
			return CompletableFuture.completedFuture(availability());
		}

		public void close() {
		}

	}

}
