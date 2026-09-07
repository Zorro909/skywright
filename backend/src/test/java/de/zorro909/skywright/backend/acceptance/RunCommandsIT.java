package de.zorro909.skywright.backend.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import de.zorro909.skywright.backend.orchestration.*;
import de.zorro909.skywright.backend.rundefinition.RunDefinition;
import de.zorro909.skywright.backend.runlifecycle.*;
import de.zorro909.skywright.backend.runsubmission.*;
import de.zorro909.skywright.backend.targetstorage.TargetStorageIntegrationTestConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.*;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("real-service")
class RunCommandsIT {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final MutableClock CLOCK = new MutableClock();

	private static final Source SOURCE = new Source();

	private static UUID storageId;

	private static String storageEndpoint;

	private static String storageBucket;

	private static final String VERSION = "sha256:" + "9".repeat(64);

	@Test
	void commandsSurviveDuplicateWorkersLostProjectionAcknowledgementsAndRestart() throws Exception {
		SOURCE.available = true;
		SOURCE.jobs = List.of();
		SOURCE.launches.set(0);
		CLOCK.now = Instant.now();
		try (var storage = SeaweedFsFixture.start();
				var admin = S3AsyncClient.builder()
					.endpointOverride(storage.endpoint())
					.region(Region.US_EAST_1)
					.credentialsProvider(
							StaticCredentialsProvider.create(AwsBasicCredentials.create("test-key", "test-secret")))
					.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
					.build()) {
			storage.awaitReady(admin);
			storageBucket = "commands-" + UUID.randomUUID();
			storageEndpoint = storage.endpoint().toString();
			admin.createBucket(b -> b.bucket(storageBucket)).join();
			try (var backend = BackendFixture.startWith(Boundaries.class, "run-commands-integration",
					"target-storage-integration")) {
				storageId = LocalRunAssemblyIT.register(backend, storage.endpoint(), storageBucket, "run-output",
						UUID.randomUUID());
				UUID run = create(backend);
				SOURCE.jobs = List.of(job(run, "RUNNING"));
				UUID cancellation = UUID.randomUUID();
				var stores = backend.bean(RunCommandStore.class);
				final var initialStores = stores;
				var delivery = backend.bean(RunCommandDelivery.class);
				try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
					var barrier = new java.util.concurrent.CyclicBarrier(2);
					java.util.concurrent.Callable<RunCommand> accept = () -> {
						barrier.await();
						return initialStores.accept(run, cancellation, RunCommand.Kind.CANCELLATION_REQUEST, "{}");
					};
					var first = workers.submit(accept);
					var second = workers.submit(accept);
					assertThat(first.get().acceptedAt()).isEqualTo(second.get().acceptedAt());
					var leaseBarrier = new java.util.concurrent.CyclicBarrier(2);
					java.util.concurrent.Callable<RunCommand> claim = () -> {
						leaseBarrier.await();
						return initialStores.claim(cancellation);
					};
					var one = workers.submit(claim);
					var two = workers.submit(claim);
					var claims = java.util.Arrays.asList(one.get(), two.get());
					assertThat(claims.stream().filter(java.util.Objects::nonNull)).hasSize(1);
					var stale = claims.stream().filter(java.util.Objects::nonNull).findFirst().orElseThrow();
					CLOCK.advance(61);
					var current = stores.claim(cancellation);
					assertThat(stores.projected(stale, CLOCK.instant())).isNull();
					stores.finish(stale, "effect-observed", false);
					assertThat(stores.get(run, cancellation).lease()).isEqualTo(current.lease());
					stores.finish(current, "delivery-unavailable", true);
				}
				var originalDeadline = stores.get(run, cancellation).forceAfter();
				CLOCK.advance(3);
				delivery.reconcile(cancellation);
				assertThat(stores.get(run, cancellation).disposition()).isEqualTo("force-accepted");
				assertThat(lifecycle(backend, run).path("state").asText()).isEqualTo("running");
				assertThat(SOURCE.cancellations).hasValue(1);
				String key = "stable-project/" + run + "/v1/control/cancellation.json";
				var projected = admin
					.getObject(b -> b.bucket(storageBucket).key(key),
							software.amazon.awssdk.core.async.AsyncResponseTransformer.toBytes())
					.join();
				assertThat(JSON.readTree(projected.asByteArray()).path("commandId").asText())
					.isEqualTo(cancellation.toString());
				backend.restart();
				stores = backend.bean(RunCommandStore.class);
				delivery = backend.bean(RunCommandDelivery.class);
				assertThat(stores.get(run, cancellation).forceAfter()).isEqualTo(originalDeadline);
				SOURCE.available = false;
				CLOCK.advance(3);
				delivery.reconcile(cancellation);
				assertThat(stores.get(run, cancellation).nextAttemptAt()).isNotNull();
				assertThat(lifecycle(backend, run).path("state").isNull()).isTrue();
				SOURCE.available = true;
				SOURCE.jobs = List.of(job(run, "CANCELLED"));
				CLOCK.advance(3);
				delivery.reconcile(cancellation);
				assertThat(stores.get(run, cancellation).disposition()).isEqualTo("effect-observed");
				assertThat(stores.get(run, cancellation).nextAttemptAt()).isNull();
				var receipt = backend.post("/api/v1/runs/" + run + "/cancellations",
						JSON.writeValueAsString(Map.of("requestId", cancellation)));
				assertThat(receipt.statusCode()).as(receipt.body()).isEqualTo(202);
				assertThat(backend.get("/api/v1/runs/" + run + "/commands/" + cancellation).body())
					.contains("effect-observed", "cancelled");
				assertThat(backend
					.post("/api/v1/runs/" + run + "/cancellations",
							JSON.writeValueAsString(Map.of("requestId", UUID.randomUUID())))
					.statusCode()).isEqualTo(409);

				// Publication succeeded but its local acknowledgement was lost.
				UUID policyRun = create(backend);
				SOURCE.jobs = List.of(job(policyRun, "RUNNING"));
				var decision = new CeilingStopDecision(UUID.randomUUID(), policyRun, CLOCK.instant().minusSeconds(2),
						JSON.readTree("{\"runtimeCeiling\":\"PT1H\"}"), List.of("runtime"),
						JSON.readTree("{\"runtimeSeconds\":3601}"), false, JSON.readTree("{\"runtime\":\"live\"}"));
				var policy = stores.accept(policyRun, decision.decisionId(), RunCommand.Kind.CEILING_STOP,
						JSON.writeValueAsString(decision));
				var lease = stores.claim(policy.id());
				Instant published = backend.bean(RunStopRequests.class)
					.deliver(backend.bean(RunAcceptanceStore.class).get(policyRun), lease);
				assertThat(stores.get(policyRun, policy.id()).projectedAt()).isNull();
				backend.restart();
				CLOCK.now = java.util.stream.Stream.of(CLOCK.instant().plusSeconds(61), published.plusSeconds(61))
					.max(Instant::compareTo)
					.orElseThrow();
				stores = backend.bean(RunCommandStore.class);
				delivery = backend.bean(RunCommandDelivery.class);
				delivery.reconcile(policy.id());
				assertThat(stores.get(policyRun, policy.id()).forceAfter()).isEqualTo(published.plusSeconds(30));
				assertThat(stores.get(policyRun, policy.id()).disposition()).isEqualTo("force-accepted");
				assertThat(stores.read(policyRun).getFirst().decidedAt()).isEqualTo(decision.decidedAt());
				SOURCE.jobs = List.of(job(policyRun, "SUCCEEDED"));
				CLOCK.advance(3);
				delivery.reconcile(policy.id());
				assertThat(stores.get(policyRun, policy.id()).disposition()).isEqualTo("no-stop-effected");
				assertThat(SOURCE.launches).hasValue(2);

				// A committed acceptance with no dispatch claim can be stopped
				// atomically.
				var input = new LocalRunRequest(UUID.randomUUID(), UUID.randomUUID(), VERSION, UUID.randomUUID(), null,
						null, "local/amd", 1, Map.of(), 1);
				var unlaunched = AcceptedRunCrashFixture.commitWithoutDispatch(backend.bean(RunAcceptanceStore.class),
						backend.bean(LocalRunAdmission.class), input);
				var fence = stores.accept(unlaunched.runId(), UUID.randomUUID(), RunCommand.Kind.CANCELLATION_REQUEST,
						"{}");
				assertThat(backend.bean(RunAcceptanceStore.class)
					.claim(unlaunched.runId(), RunJobAdapter.taskFingerprint(unlaunched.task()))
					.toCompletableFuture()
					.join()).isEqualTo(LaunchDispatchGate.Decision.STOP_REQUESTED);
				SOURCE.available = false;
				delivery.reconcile(fence.id());
				delivery.reconcile(unlaunched.submissionId());
				assertThat(stores.get(unlaunched.runId(), fence.id()).disposition()).isEqualTo("dispatch-prevented");
				assertThat(lifecycle(backend, unlaunched.runId()).path("state").asText()).isEqualTo("cancelled");
				assertThat(SOURCE.launches).hasValue(2);
			}
		}
	}

	private static UUID create(BackendFixture backend) throws Exception {
		var response = backend.post("/api/v1/runs",
				JSON.writeValueAsString(Map.of("submissionId", UUID.randomUUID(), "trainingProjectId",
						UUID.randomUUID(), "manifestArtifactDigest", VERSION, "datasetDefinitionId", UUID.randomUUID(),
						"target", "local/amd", "gpuCount", 1, "maximumRecoveryDebt", 1, "configuration", Map.of())));
		assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
		return UUID.fromString(JSON.readTree(response.body()).path("runId").asText());
	}

	private static JsonNode lifecycle(BackendFixture backend, UUID run) throws Exception {
		var response = backend.get("/api/v1/runs/" + run);
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		return JSON.readTree(response.body()).path("lifecycle");
	}

	private static OperationOutcome.ManagedJobStatus job(UUID run, String status) {
		return new OperationOutcome.ManagedJobStatus(42L, "skywright-" + run, status, 0, 0, 100., 110.,
				status.equals("RUNNING") ? null : 150., null, "generation", "kubernetes", "local", null, "MI300X:1",
				null, "worker");
	}

	@Configuration(proxyBeanMethods = false)
	@Profile("run-commands-integration")
	@Import(TargetStorageIntegrationTestConfiguration.class)
	static class Boundaries {

		@Bean
		@Primary
		java.time.Clock commandClock() {
			return CLOCK;
		}

		@Bean
		@Primary
		Orchestrator lifecycleSource() {
			return SOURCE;
		}

		@Bean
		@Primary
		LocalRunAdmission lifecycleAdmission() {
			return (run, request) -> {
				try {
					var document = JSON.readTree(Files.readString(Path.of(System.getProperty("repository.root"),
							"sdk/tests/fixtures/managed-runtime/definition.json")));
					((tools.jackson.databind.node.ObjectNode) document.at("/storage/execution")).put("storageId",
							storageId.toString());
					var execution = (tools.jackson.databind.node.ObjectNode) document.at("/storage/execution");
					execution.put("endpoint", storageEndpoint)
						.put("bucket", storageBucket)
						.put("region", "us-east-1")
						.put("addressingMode", "path");
					execution.set("compatibilityOptions", JSON.createObjectNode());
					((tools.jackson.databind.node.ObjectNode) document.path("executionPolicy"))
						.put("maximumRecoveryDebt", 1)
						.put("runtimeCeiling", "PT1H");
					var task = new OrchestratorTaskSpecification("skywright-" + run, null, "train",
							List.of(new OrchestratorTaskSpecification.Resources("kubernetes/local", "8", "32",
									"MI300X:1", "docker:fixture", false)),
							Map.of());
					return new LocalRunAdmission.Prepared(RunDefinition.decode(document.toString()), task, null);
				}
				catch (java.io.IOException failure) {
					throw new java.io.UncheckedIOException(failure);
				}
			};
		}

	}

	static class Source extends LocalRunAcceptanceIT.Source {

		final AtomicInteger cancellations = new AtomicInteger();

		@Override
		public java.util.concurrent.CompletionStage<OrchestratorResult<OrchestratorOperation>> control(
				ControlRequest request) {
			cancellations.incrementAndGet();
			return java.util.concurrent.CompletableFuture.completedFuture(
					available ? OrchestratorResult.accepted(new OrchestratorOperation("cancel", OperationKind.CONTROL))
							: OrchestratorResult.failure(
									BridgeFailure.unavailable(BridgeFailure.FailureCause.REACHABILITY, "offline")));
		}

		@Override
		public java.util.concurrent.CompletionStage<OrchestratorResult<OperationOutcome>> complete(
				OrchestratorOperation operation) {
			if (operation.kind() == OperationKind.CONTROL)
				return java.util.concurrent.CompletableFuture
					.completedFuture(OrchestratorResult.accepted(new OperationOutcome.Controlled(true)));
			return super.complete(operation);
		}

	}

	static class MutableClock extends java.time.Clock {

		volatile Instant now = Instant.now();

		public java.time.ZoneId getZone() {
			return java.time.ZoneOffset.UTC;
		}

		public java.time.Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		public Instant instant() {
			return now;
		}

		void advance(long seconds) {
			now = now.plusSeconds(seconds);
		}

	}

}
