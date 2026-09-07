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
class RunLifecycleIT {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Source SOURCE = new Source();

	private static UUID storageId;

	private static String storageEndpoint;

	private static String storageBucket;

	private static final String VERSION = "sha256:" + "9".repeat(64);

	@TempDir
	Path directory;

	@Test
	void actualSdkHistoryAndAdapterFactsDeriveLifecycleAcrossRecoveryPurgeAndBackendRestart() throws Exception {
		SOURCE.available = true;
		SOURCE.jobs = List.of();
		try (var storage = SeaweedFsFixture.start();
				var admin = S3AsyncClient.builder()
					.endpointOverride(storage.endpoint())
					.region(Region.US_EAST_1)
					.credentialsProvider(
							StaticCredentialsProvider.create(AwsBasicCredentials.create("test-key", "test-secret")))
					.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
					.build()) {
			storage.awaitReady(admin);
			String bucket = "lifecycle-" + UUID.randomUUID();
			admin.createBucket(b -> b.bucket(bucket)).join();
			try (var backend = BackendFixture.startWith(Boundaries.class, "run-lifecycle-integration",
					"target-storage-integration")) {
				storageId = LocalRunAssemblyIT.register(backend, storage.endpoint(), bucket, "run-output",
						UUID.randomUUID());
				storageEndpoint = storage.endpoint().toString();
				storageBucket = bucket;
				UUID run = create(backend);
				SOURCE.jobs = List.of(job(run, "RUNNING"));
				var running = lifecycle(backend, run);
				assertThat(running.path("state").asText()).isEqualTo("running");
				assertThat(Instant.parse(running.path("skyPilotReadAt").asText()))
					.isBeforeOrEqualTo(Instant.parse(running.path("runStoreReadAt").asText()));
				assertThat(Instant.parse(running.path("runStoreReadAt").asText()))
					.isBeforeOrEqualTo(Instant.parse(running.path("fetchedAt").asText()));
				SOURCE.available = false;
				var unavailable = lifecycle(backend, run);
				assertThat(unavailable.path("state").isNull() || unavailable.path("state").isMissingNode()).isTrue();
				assertThat(unavailable.at("/lastSeen/state").asText()).isEqualTo("running");
				assertThat(unavailable.path("sourceAvailability").asText()).isEqualTo("unavailable");
				SOURCE.available = true;

				var firstDirectory = Files.createDirectory(directory.resolve("completed"));
				var interrupted = executeSdk(storage.endpoint().toString(), bucket, run, firstDirectory, "interrupted",
						75);
				assertThat(interrupted.path("cause").asText()).isEqualTo("interrupted");
				assertThat(lifecycle(backend, run).path("state").asText()).isEqualTo("interrupted");
				proveStopped(firstDirectory, run);
				var completed = executeSdk(storage.endpoint().toString(), bucket, run, firstDirectory, "complete", 0);
				assertThat(completed.path("step").asLong()).isEqualTo(2);
				assertThat(lifecycle(backend, run).path("state").asText()).isEqualTo("finished");
				SOURCE.jobs = List.of(job(run, "SUCCEEDED"));
				assertThat(lifecycle(backend, run).path("terminalLatched").asBoolean()).isTrue();

				// Checkpoint payload retention does not remove journal publication
				// evidence.
				var keys = admin
					.listObjectsV2(b -> b.bucket(bucket).prefix("stable-project/" + run + "/v1/checkpoints/"))
					.join()
					.contents();
				assertThat(keys).isNotEmpty();
				for (var key : keys)
					admin.deleteObject(b -> b.bucket(bucket).key(key.key())).join();
				SOURCE.available = false;
				backend.restart();
				var retained = lifecycle(backend, run);
				assertThat(retained.path("state").asText()).isEqualTo("finished");
				assertThat(retained.path("sourceAvailability").asText()).isEqualTo("unavailable");
				assertThat(retained.path("cause").asText()).isEqualTo("completed");
				SOURCE.available = true;
				SOURCE.jobs = List.of();
				assertThat(lifecycle(backend, run).path("state").asText()).isEqualTo("finished");
				var page = backend.get("/api/v1/runs?limit=1");
				assertThat(page.statusCode()).as(page.body()).isEqualTo(200);
				assertThat(JSON.readTree(page.body()).at("/items/0/lifecycle/state").asText()).isEqualTo("finished");
				assertThat(backend.bean(RunLifecycleReads.class).read(run).lifecycle().state()).isEqualTo("finished");

				UUID exhaustedRun = create(backend);
				var exhaustedDirectory = Files.createDirectory(directory.resolve("exhausted"));
				executeSdk(storage.endpoint().toString(), bucket, exhaustedRun, exhaustedDirectory, "crash", 137);
				proveStopped(exhaustedDirectory, exhaustedRun);
				executeSdk(storage.endpoint().toString(), bucket, exhaustedRun, exhaustedDirectory, "crash", 137);
				proveStopped(exhaustedDirectory, exhaustedRun);
				var refused = executeSdk(storage.endpoint().toString(), bucket, exhaustedRun, exhaustedDirectory,
						"complete", 1);
				assertThat(refused.path("code").asText()).isEqualTo("RECOVERY_EXHAUSTED");
				SOURCE.jobs = List.of(job(exhaustedRun, "RUNNING"));
				var exhausted = lifecycle(backend, exhaustedRun);
				assertThat(exhausted.path("state").asText()).isEqualTo("failed");
				assertThat(exhausted.path("cause").isNull() || exhausted.path("cause").isMissingNode()).isTrue();

				UUID unseen = create(backend);
				SOURCE.jobs = List.of(job(exhaustedRun, "FAILED"), job(unseen, "FAILED_NO_RESOURCE"));
				int before = SOURCE.observations.get();
				new RunRetentionReconciler(backend.bean(RunAcceptanceStore.class), backend.bean(RunJobAdapter.class))
					.sweep();
				assertThat(SOURCE.observations.get() - before).isEqualTo(2);
				SOURCE.available = false;
				backend.restart();
				assertThat(lifecycle(backend, unseen).path("state").asText()).isEqualTo("failed");
				assertThat(lifecycle(backend, exhaustedRun).path("state").asText()).isEqualTo("failed");
				int after = SOURCE.observations.get();
				new RunRetentionReconciler(backend.bean(RunAcceptanceStore.class), backend.bean(RunJobAdapter.class))
					.sweep();
				assertThat(SOURCE.observations.get()).isEqualTo(after);
				assertThat(SOURCE.launches.get()).isEqualTo(3);
				SOURCE.available = true;
				UUID ambiguousRun = create(backend);
				SOURCE.available = true;
				SOURCE.jobs = List.of(job(ambiguousRun, "SUCCEEDED"), job(ambiguousRun, "RUNNING"));
				assertThat(lifecycle(backend, ambiguousRun).path("state").isNull()).isTrue();
				SOURCE.available = false;
				assertThat(lifecycle(backend, ambiguousRun).path("state").isNull()).isTrue();
				SOURCE.available = true;
				SOURCE.jobs = List.of(job(ambiguousRun, "FAILED"));
				new RunRetentionReconciler(backend.bean(RunAcceptanceStore.class), backend.bean(RunJobAdapter.class))
					.sweep();
				SOURCE.available = false;
				assertThat(lifecycle(backend, ambiguousRun).path("state").asText()).isEqualTo("failed");
				try (var replacement = SeaweedFsFixture.start();
						var replacementAdmin = S3AsyncClient.builder()
							.endpointOverride(replacement.endpoint())
							.region(Region.US_EAST_1)
							.credentialsProvider(StaticCredentialsProvider
								.create(AwsBasicCredentials.create("test-key", "test-secret")))
							.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
							.build()) {
					replacement.awaitReady(replacementAdmin);
					replacementAdmin.createBucket(b -> b.bucket(bucket)).join();
					var registration = JSON.readTree(backend.get("/api/v1/target-storages/" + storageId).body());
					var promoted = backend.post("/api/v1/target-storages/" + storageId + "/revisions",
							JSON.writeValueAsString(Map.of("expectedRegistrationRevision",
									registration.path("registrationRevision").asLong(), "configuration",
									Map.of("endpoint", replacement.endpoint(), "region", "us-east-1", "pathStyleAccess",
											true, "compatibilityOptions", Map.of()))));
					assertThat(promoted.statusCode()).as(promoted.body()).isEqualTo(200);
					assertThat(JSON.readTree(promoted.body()).path("activeRevision").asLong()).isEqualTo(2);
					assertThat(lifecycle(backend, run).path("state").asText()).isEqualTo("finished");
				}
				assertThat(lifecycle(backend, run).path("state").asText()).isEqualTo("finished");
				String relocatedBucket = "relocated-" + UUID.randomUUID();
				admin.createBucket(b -> b.bucket(relocatedBucket)).join();
				UUID relocatedStorage = LocalRunAssemblyIT.register(backend, storage.endpoint(), relocatedBucket,
						"run-output", UUID.randomUUID());
				String prefix = "stable-project/" + run + "/v1/";
				var records = admin.listObjectsV2(b -> b.bucket(bucket).prefix(prefix)).join().contents();
				for (var record : records) {
					var content = admin
						.getObject(b -> b.bucket(bucket).key(record.key()),
								software.amazon.awssdk.core.async.AsyncResponseTransformer.toBytes())
						.join();
					admin
						.putObject(
								b -> b.bucket(relocatedBucket)
									.key(record.key())
									.metadata(content.response().metadata())
									.contentType(content.response().contentType()),
								software.amazon.awssdk.core.async.AsyncRequestBody.fromBytes(content.asByteArray()))
						.join();
				}
				// Exercise the location reader's seam; #53 owns the verified move
				// protocol.
				try (var connection = backend.bean(javax.sql.DataSource.class).getConnection();
						var update = connection.prepareStatement(
								"update skywright.run_store_location set storage_id=?, descriptor_json=? where run_id=?")) {
					update.setObject(1, relocatedStorage);
					var moved = (tools.jackson.databind.node.ObjectNode) backend.bean(RunAcceptanceStore.class)
						.currentStorage(run);
					moved.put("storageId", relocatedStorage.toString()).put("bucket", relocatedBucket);
					update.setString(2, moved.toString());
					update.setObject(3, run);
					assertThat(update.executeUpdate()).isEqualTo(1);
				}
				for (var record : records)
					admin.deleteObject(b -> b.bucket(bucket).key(record.key())).join();
				assertThat(lifecycle(backend, run).path("state").asText()).isEqualTo("finished");
				var registration = JSON.readTree(backend.get("/api/v1/target-storages/" + relocatedStorage).body());
				var deactivated = backend.put("/api/v1/target-storages/" + relocatedStorage + "/activation",
						JSON.writeValueAsString(Map.of("expectedRegistrationRevision",
								registration.path("registrationRevision").asLong(), "activated", false)));
				assertThat(deactivated.statusCode()).as(deactivated.body()).isEqualTo(200);
				assertThat(lifecycle(backend, run).path("state").asText()).isEqualTo("finished");
				var deletion = backend.delete("/api/v1/target-storages/" + relocatedStorage);
				assertThat(deletion.statusCode()).as(deletion.body()).isEqualTo(409);
				assertThat(deletion.body()).contains("SKYWRIGHT_TARGET_STORAGE_REFERENCED");
				String reportKey = prefix + "attempts/" + completed.path("attempt_id").asText() + "/report.json";
				var original = admin
					.getObject(b -> b.bucket(relocatedBucket).key(reportKey),
							software.amazon.awssdk.core.async.AsyncResponseTransformer.toBytes())
					.join();
				var invalid = (tools.jackson.databind.node.ObjectNode) JSON.readTree(original.asByteArray());
				invalid.put("runId", UUID.randomUUID().toString());
				byte[] bytes = JSON.writeValueAsBytes(invalid);
				var metadata = new java.util.HashMap<>(original.response().metadata());
				metadata.put("skywright-size", Integer.toString(bytes.length));
				metadata.put("skywright-sha256", java.util.HexFormat.of()
					.formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
				admin
					.putObject(
							b -> b.bucket(relocatedBucket)
								.key(reportKey)
								.metadata(metadata)
								.contentType("application/json"),
							software.amazon.awssdk.core.async.AsyncRequestBody.fromBytes(bytes))
					.join();
				var corrupt = lifecycle(backend, run);
				assertThat(corrupt.path("processAvailability").asText()).isEqualTo("invalid");
				assertThat(corrupt.path("state").isNull() || corrupt.path("state").isMissingNode()).isTrue();

			}
		}
	}

	private JsonNode executeSdk(String endpoint, String bucket, UUID run, Path work, String mode, int expected)
			throws Exception {
		var root = Path.of(System.getProperty("repository.root"));
		var output = directory.resolve("sdk-" + UUID.randomUUID() + ".log");
		var child = new ProcessBuilder("uv", "run", "--locked", "--group", "ml-test", "python",
				"tests/support/recovery_process_scenario.py")
			.directory(root.resolve("sdk").toFile())
			.redirectOutput(output.toFile())
			.redirectError(ProcessBuilder.Redirect.appendTo(output.toFile()))
			.start();
		var settings = Map.ofEntries(Map.entry("directory", work.toString()), Map.entry("mode", mode),
				Map.entry("endpoint", endpoint), Map.entry("bucket", bucket), Map.entry("run_id", run.toString()),
				Map.entry("project_id", "stable-project"), Map.entry("project_version", VERSION),
				Map.entry("access_key", "test-key"), Map.entry("secret_key", "test-secret"),
				Map.entry("maximum_debt", 1));
		try (var input = child.getOutputStream()) {
			input.write(JSON.writeValueAsBytes(settings));
		}
		try {
			assertThat(child.waitFor(180, TimeUnit.SECONDS)).as("SDK process completion").isTrue();
			String text = Files.readString(output);
			assertThat(child.exitValue()).as(text).isEqualTo(expected);
			if (expected == 137)
				return JSON.createObjectNode();
			return JSON.readTree(text.lines().filter(line -> line.startsWith("{")).reduce((a, b) -> b).orElseThrow());
		}
		finally {
			if (child.isAlive())
				child.destroyForcibly().waitFor();
		}
	}

	private static void proveStopped(Path work, UUID run) throws Exception {
		String attempt = JSON.readTree(Files.readString(work.resolve("attempt.json"))).path("attempt_id").asText();
		Files.writeString(work.resolve("stopped-proof.json"),
				JSON.writeValueAsString(Map.of("run_id", run.toString(), "attempt_id", attempt, "condition", "stopped",
						"reference", "parent waitFor reaped this exact process")));
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
	@Profile("run-lifecycle-integration")
	@Import(TargetStorageIntegrationTestConfiguration.class)
	static class Boundaries {

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
						.put("maximumRecoveryDebt", 1);
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

		final AtomicInteger observations = new AtomicInteger();

		@Override
		public java.util.concurrent.CompletionStage<OrchestratorResult<OrchestratorOperation>> observe(
				StatusRequest request) {
			observations.incrementAndGet();
			return super.observe(request);
		}

	}

}
