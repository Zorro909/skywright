package de.zorro909.skywright.backend.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import de.zorro909.skywright.backend.credential.*;
import de.zorro909.skywright.backend.datasetcatalog.*;
import de.zorro909.skywright.backend.orchestration.*;
import de.zorro909.skywright.backend.projectversion.*;
import de.zorro909.skywright.backend.runsubmission.LocalRunTargetSettings;
import de.zorro909.skywright.backend.targetstorage.TargetStorageIntegrationTestConfiguration;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import tools.jackson.databind.json.JsonMapper;

@Tag("real-service")
class LocalRunAssemblyIT {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final UUID DATASET_BINDING = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private static final UUID OUTPUT_BINDING = UUID.fromString("00000000-0000-0000-0000-000000000005");

	private static final UUID SHARED_IDENTITY_BINDING = UUID.fromString("00000000-0000-0000-0000-000000000006");

	private static URI vaultEndpoint;

	private static Path tokenFile;

	private static volatile boolean refuseCredentials;

	@Test
	void productionAdmissionResolvesContractsLeasesStorageAndSeparateCredentialChannels() throws Exception {
		var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		tokenFile = Files.createTempFile("run-assembly-token", ".txt");
		Files.writeString(tokenFile, "fixture-token");
		server.createContext("/v1/skywright/data/", exchange -> {
			if (refuseCredentials) {
				exchange.sendResponseHeaders(403, -1);
				exchange.close();
				return;
			}
			String identity = exchange.getRequestURI().getPath().endsWith("dataset") ? "dataset-reader" : "run-writer";
			byte[] body = JSON.writeValueAsBytes(Map.of("data", Map.of("metadata", Map.of("version", 1), "data",
					Map.of("accessKeyId", identity, "secretAccessKey", identity + "-secret"))));
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		vaultEndpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
		try (var storage = SeaweedFsFixture.start();
				var admin = S3AsyncClient.builder()
					.endpointOverride(storage.endpoint())
					.credentialsProvider(
							StaticCredentialsProvider.create(AwsBasicCredentials.create("test-key", "test-secret")))
					.region(Region.US_EAST_1)
					.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
					.build()) {
			storage.awaitReady(admin);
			String datasetBucket = "datasets-" + UUID.randomUUID();
			String outputBucket = "outputs-" + UUID.randomUUID();
			admin.createBucket(b -> b.bucket(datasetBucket)).join();
			admin.createBucket(b -> b.bucket(outputBucket)).join();
			try (var backend = BackendFixture.startWith(Boundaries.class, "local-run-assembly-integration",
					"target-storage-integration")) {
				UUID datasetStorage = register(backend, storage.endpoint(), datasetBucket, "dataset", DATASET_BINDING);
				UUID outputStorage = register(backend, storage.endpoint(), outputBucket, "run-output", OUTPUT_BINDING);
				var defaults = backend.put("/api/v1/target-storage-defaults/local-single-gpu",
						"{\"executionStorageId\":\"" + outputStorage
								+ "\",\"repatriationEnabled\":false,\"repatriationStorageId\":\"" + outputStorage
								+ "\"}");
				assertThat(defaults.statusCode()).as(defaults.body()).isEqualTo(200);
				var project = backend.post("/api/v1/training-projects",
						"{\"displayName\":\"Local project\",\"registry\":{\"repository\":\"ghcr.io/example/local\",\"accessMode\":\"public\"}}");
				assertThat(project.statusCode()).as(project.body()).isEqualTo(201);
				String projectId = JSON.readTree(project.body()).path("id").asText();
				backend.bean(Registry.class).projectId = projectId;
				var data = JSON.readTree(fixture("dataset.json"));
				UUID datasetId = UUID.randomUUID(), definitionId = UUID.randomUUID(), copyId = UUID.randomUUID();
				var entries = new java.util.ArrayList<DatasetManifestEntry>();
				for (var item : data.path("objects"))
					entries.add(new DatasetManifestEntry(item.path("object_key").asText(),
							item.path("byte_count").asLong(), Base64.getEncoder()
								.encodeToString(HexFormat.of().parseHex(item.path("sha256").asText().substring(7)))));
				backend.bean(DatasetCatalog.class)
					.publish(new DatasetPublication(datasetId, definitionId, "v1", "mosaicml-streaming-mds@2",
							data.path("contentFingerprint").asText(), data.path("manifestIdentity").asText(), copyId,
							datasetStorage, "datasets/v1",
							entries.stream().mapToLong(DatasetManifestEntry::byteCount).sum(), Instant.now(), entries));
				UUID submission = UUID.randomUUID();
				String request = JSON.writeValueAsString(Map.of("submissionId", submission, "trainingProjectId",
						projectId, "manifestArtifactDigest", "sha256:" + "9".repeat(64), "datasetDefinitionId",
						definitionId, "target", "local/amd", "gpuCount", 1, "configuration", Map.of()));
				var registry = backend.bean(Registry.class);
				registry.holdAdmission = true;
				var accepting = CompletableFuture.supplyAsync(() -> {
					try {
						return backend.post("/api/v1/runs", request);
					}
					catch (Exception failure) {
						throw new RuntimeException(failure);
					}
				});
				assertThat(registry.admissionEntered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
				var promoting = CompletableFuture.supplyAsync(() -> {
					try {
						return backend.post("/api/v1/training-projects/" + projectId + "/registry-rebindings",
								"{\"expectedRevision\":1,\"candidate\":{\"repository\":\"ghcr.io/example/local-replacement\",\"accessMode\":\"public\"}}");
					}
					catch (Exception failure) {
						throw new RuntimeException(failure);
					}
				});
				try {
					assertProjectLockBlocked(backend);
					assertThat(promoting).isNotDone();
				}
				finally {
					registry.releaseAdmission.countDown();
				}
				var accepted = accepting.get(20, java.util.concurrent.TimeUnit.SECONDS);
				var promoted = promoting.get(20, java.util.concurrent.TimeUnit.SECONDS);
				assertThat(promoted.statusCode()).as(promoted.body()).isEqualTo(201);
				assertThat(JSON.readTree(promoted.body()).path("state").asText()).isEqualTo("promoted");
				assertThat(JSON.readTree(promoted.body()).path("artifacts").size()).isEqualTo(5);
				assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
				assertThat(accepted.body()).contains("\"handoff\":\"source-accepted\"")
					.doesNotContain("reader-secret", "writer-secret", "fixture-token");
				UUID run = UUID.fromString(JSON.readTree(accepted.body()).path("runId").asText());
				assertThat(backend.bean(DatasetCatalog.class).get(definitionId).leases()).singleElement()
					.satisfies(lease -> assertThat(lease.runRecordId()).isEqualTo(run));
				assertThat(backend.bean(LocalProjectionFacts.class).forConsumer(run)).hasSize(2);
				assertThat(backend
					.bean(de.zorro909.skywright.backend.trainingproject.TrainingProjectArtifactReferences.class)
					.referencedArtifacts(UUID.fromString(projectId))).hasSize(5);
				var source = backend.bean(AssemblySource.class);
				assertThat(source.separateCredentials).isTrue();
				var matcher = java.util.regex.Pattern
					.compile("materials.json'\\).write_bytes\\(base64.b64decode\\('([^']+)'")
					.matcher(source.task.run());
				assertThat(matcher.find()).isTrue();
				var materials = JSON.readTree(Base64.getDecoder().decode(matcher.group(1)));
				assertThat(materials.at("/dataset/objects/0/sha256").asText()).startsWith("sha256:").hasSize(71);
				assertThat(materials.at("/datasetLocation/storage_id").asText()).isEqualTo(datasetStorage.toString());
				assertThat(materials.at("/datasetLocation/lease_id").asText()).isEqualTo(
						backend.bean(DatasetCatalog.class).get(definitionId).leases().getFirst().id().toString());
				assertThat(source.task.run()).doesNotContain("reader-secret", "writer-secret");
				refuseCredentials = true;
				try {
					var refused = backend.post("/api/v1/runs",
							request.replace(submission.toString(), UUID.randomUUID().toString()));
					assertThat(refused.statusCode()).as(refused.body()).isEqualTo(503);
					assertThat(backend.bean(DatasetCatalog.class).get(definitionId).leases()).hasSize(1);
					try (var connection = backend.bean(javax.sql.DataSource.class).getConnection();
							var statement = connection.createStatement()) {
						try (var rows = statement
							.executeQuery("select count(*) from skywright.local_credential_projection")) {
							rows.next();
							assertThat(rows.getLong(1)).isEqualTo(2);
						}
					}
				}
				finally {
					refuseCredentials = false;
				}

				registry.unavailable = true;
				try {
					var refused = backend.post("/api/v1/runs",
							request.replace(submission.toString(), UUID.randomUUID().toString()));
					assertThat(refused.statusCode()).as(refused.body()).isEqualTo(503);
					assertThat(refused.body()).contains("SKYWRIGHT_RUN_ADMISSION_UNAVAILABLE",
							"PROJECT_REGISTRY_UNAVAILABLE", "\"retryable\":true");
					assertThat(backend.bean(DatasetCatalog.class).get(definitionId).leases()).hasSize(1);
				}
				finally {
					registry.unavailable = false;
				}

				String sharedBucket = "shared-identity-" + UUID.randomUUID();
				admin.createBucket(b -> b.bucket(sharedBucket)).join();
				UUID sharedStorage = register(backend, storage.endpoint(), sharedBucket, "run-output",
						SHARED_IDENTITY_BINDING);
				var sharedRequest = (tools.jackson.databind.node.ObjectNode) JSON.readTree(request);
				sharedRequest.put("submissionId", UUID.randomUUID().toString());
				sharedRequest.put("executionStorageId", sharedStorage.toString());
				var shared = backend.post("/api/v1/runs", sharedRequest.toString());
				assertThat(shared.statusCode()).as(shared.body()).isEqualTo(422);
				assertThat(shared.body()).contains("SKYWRIGHT_TRAINING_CREDENTIAL_ISOLATION_INVALID");
				assertThat(backend.bean(DatasetCatalog.class).get(definitionId).leases()).hasSize(1);

				var invalid = backend.post("/api/v1/runs",
						request.replace(submission.toString(), UUID.randomUUID().toString())
							.replace("\"gpuCount\":1", "\"gpuCount\":2"));
				assertThat(invalid.statusCode()).as(invalid.body()).isEqualTo(422);
				assertThat(backend.bean(DatasetCatalog.class).get(definitionId).leases()).hasSize(1);
				var afterPromotion = backend.post("/api/v1/runs",
						request.replace(submission.toString(), UUID.randomUUID().toString()));
				assertThat(afterPromotion.statusCode()).as(afterPromotion.body()).isEqualTo(202);
				assertThat(source.task.resources().getFirst().imageId())
					.startsWith("docker:ghcr.io/example/local-replacement@");
			}
		}
		finally {
			server.stop(0);
			Files.deleteIfExists(tokenFile);
		}
	}

	private static void assertProjectLockBlocked(BackendFixture backend) throws Exception {
		long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
		try (var connection = backend.bean(javax.sql.DataSource.class).getConnection();
				var statement = connection.createStatement()) {
			while (System.nanoTime() < deadline) {
				try (var rows = statement
					.executeQuery("select count(*) from pg_stat_activity where datname=current_database() "
							+ "and wait_event_type='Lock' and query like '%training_project%'")) {
					rows.next();
					if (rows.getLong(1) > 0)
						return;
				}
				Thread.sleep(25);
			}
		}
		throw new AssertionError("Registry promotion did not wait for Run acceptance's project lock");
	}

	private static UUID register(BackendFixture backend, URI endpoint, String bucket, String purpose, UUID training)
			throws Exception {
		String body = JSON
			.writeValueAsString(Map.of(
					"name", bucket, "purpose", purpose, "bucket", bucket, "configuration", Map.of("endpoint", endpoint,
							"region", "us-east-1", "pathStyleAccess", true, "compatibilityOptions", Map.of()),
					"bindings",
					List.of(Map.of("role", "training-process", "bindingId", training, "bindingRevision", 1),
							Map.of("role", "backend", "bindingId", UUID.randomUUID(), "bindingRevision", 1),
							Map.of("role", "transfer-worker", "bindingId", UUID.randomUUID(), "bindingRevision", 1),
							Map.of("role", "metric-view", "bindingId", UUID.randomUUID(), "bindingRevision", 1))));
		var result = backend.post("/api/v1/target-storages", body);
		assertThat(result.statusCode()).as(result.body()).isEqualTo(201);
		UUID id = UUID.fromString(JSON.readTree(result.body()).path("id").asText());
		var activation = backend.put("/api/v1/target-storages/" + id + "/activation",
				"{\"expectedRegistrationRevision\":2,\"activated\":true}");
		assertThat(activation.statusCode()).as(activation.body()).isEqualTo(200);
		return id;
	}

	private static String fixture(String name) throws java.io.IOException {
		return Files
			.readString(Path.of(System.getProperty("repository.root"), "sdk/tests/fixtures/managed-runtime", name))
			.stripTrailing();
	}

	@Configuration(proxyBeanMethods = false)
	@Profile("local-run-assembly-integration")
	@Import(TargetStorageIntegrationTestConfiguration.class)
	static class Boundaries {

		@Bean
		@Primary
		LocalRunTargetSettings qualifiedLocalTarget() {
			return new LocalRunTargetSettings("local/amd", "local", "MI300X", 1, 192L * 1024 * 1024 * 1024, "8", "32");
		}

		@Bean
		@Primary
		Registry localRegistry() {
			return new Registry();
		}

		@Bean
		@Primary
		AssemblySource assemblySource() {
			return new AssemblySource();
		}

		@Bean
		VaultBindings fixtureVault() {
			return new VaultBindings(vaultEndpoint, "skywright", tokenFile,
					List.of(binding(DATASET_BINDING, "dataset", "read-only"),
							binding(OUTPUT_BINDING, "output", "read-write-delete"),
							new CredentialBinding(SHARED_IDENTITY_BINDING, 1, "fixtures/shared",
									CredentialBinding.Kind.S3, "shared-output", "training-process", "dataset", "bucket",
									"read-write-delete", Instant.parse("2026-01-01T00:00:00Z"), null, true)),
					Clock.systemUTC());
		}

		@Bean
		LocalCredentialProjections fixtureCredentialBroker(VaultBindings bindings, LocalProjectionFacts facts) {
			return new LocalCredentialProjections(bindings, facts);
		}

		private static CredentialBinding binding(UUID id, String name, String profile) {
			return new CredentialBinding(id, 1, "fixtures/" + name, CredentialBinding.Kind.S3, name, "training-process",
					name, "bucket", profile, Instant.parse("2026-01-01T00:00:00Z"), null, true);
		}

	}

	static class Registry implements ProjectVersionRegistry {

		String projectId;

		volatile boolean unavailable;

		volatile boolean holdAdmission;

		final java.util.concurrent.CountDownLatch admissionEntered = new java.util.concurrent.CountDownLatch(1);

		final java.util.concurrent.CountDownLatch releaseAdmission = new java.util.concurrent.CountDownLatch(1);

		public List<ProjectVersionReference> listVersions(String repository) {
			return List.of();
		}

		public boolean imageAvailable(String repository, String digest) {
			return true;
		}

		public Optional<RegistryArtifact> pullArtifact(String repository, String digest) {
			if (unavailable)
				throw new IllegalStateException("Registry is offline");
			if (holdAdmission && repository.equals("ghcr.io/example/local")) {
				holdAdmission = false;
				admissionEntered.countDown();
				try {
					if (!releaseAdmission.await(20, java.util.concurrent.TimeUnit.SECONDS))
						throw new IllegalStateException("Admission was not released");
				}
				catch (InterruptedException failure) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(failure);
				}
			}
			try {
				if (digest.equals("sha256:" + "e".repeat(64)))
					return Optional.of(new RegistryArtifact(digest, fixture("configuration.json")));
				if (digest.equals("sha256:" + "f".repeat(64)))
					return Optional.of(new RegistryArtifact(digest, fixture("metrics.json")));
				var version = (tools.jackson.databind.node.ObjectNode) JSON.readTree(fixture("definition.json"))
					.path("trainingProjectVersion");
				version.remove("manifestArtifactDigest");
				version.put("manifestVersion", 1);
				version.put("projectIdentity", projectId);
				version.set("acceleratorBackends", JSON.valueToTree(List.of("cuda", "rocm")));
				version.set("contractArtifacts", JSON.valueToTree(Map.of("cuda",
						Map.of("configuration", "sha256:" + "e".repeat(64), "metrics", "sha256:" + "f".repeat(64)),
						"rocm",
						Map.of("configuration", "sha256:" + "e".repeat(64), "metrics", "sha256:" + "f".repeat(64)))));
				return Optional.of(new RegistryArtifact(digest, version.toString()));
			}
			catch (java.io.IOException failure) {
				throw new IllegalStateException(failure);
			}
		}

	}

	static class AssemblySource extends LocalRunAcceptanceIT.Source {

		OrchestratorTaskSpecification task;

		boolean separateCredentials;

		AssemblySource() {
			available = true;
		}

		@Override
		public CompletionStage<OrchestratorResult<OrchestratorOperation>> submit(OrchestratorTaskSpecification task,
				TrainingCredentials credentials) {
			this.task = task;
			separateCredentials = credentials
				.send(values -> values.get("SKYWRIGHT_DATASET_ACCESS_KEY_ID").equals("dataset-reader")
						&& values.get("SKYWRIGHT_RUN_STORE_ACCESS_KEY_ID").equals("run-writer"));
			return CompletableFuture.completedFuture(
					OrchestratorResult.accepted(new OrchestratorOperation("launch", OperationKind.SUBMISSION)));
		}

		@Override
		public CompletionStage<OrchestratorResult<OperationOutcome>> complete(OrchestratorOperation operation) {
			return operation.kind() == OperationKind.SUBMISSION
					? CompletableFuture
						.completedFuture(OrchestratorResult.accepted(new OperationOutcome.Submitted(1, null)))
					: super.complete(operation);
		}

	}

}
