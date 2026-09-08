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
class RunLogArchiveIT {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final RunCommandsIT.MutableClock CLOCK = new RunCommandsIT.MutableClock();

	private static final LocalRunAcceptanceIT.Source SOURCE = new LocalRunAcceptanceIT.Source();

	private static UUID storageId;

	private static String storageEndpoint;

	private static String storageBucket;

	private static final String VERSION = "sha256:" + "9".repeat(64);

	@Test
	void rawArchiveSurvivesLostCursorAcknowledgementRestartAndSourceLoss() throws Exception {
		SOURCE.available = true;
		SOURCE.jobs = List.of();
		CLOCK.now = Instant.now();
		byte[] task = new byte[2 * 1024 * 1024 + 17];
		new java.util.Random(42).nextBytes(task);
		// Keep this fixture a setup-only failure with no accidental marker.
		for (int i = 0; i < task.length; i++)
			if (task[i] == 30)
				task[i] = 31;
		byte[] controller = "controller\r\n\033[31mfailed\033[0m\r".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		var requests = new AtomicInteger();
		var terminal = new java.util.concurrent.atomic.AtomicBoolean();
		var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/page", exchange -> {
			var input = JSON.readTree(exchange.getRequestBody().readAllBytes());
			byte[] all = input.path("stream").asText().equals("task") ? task : controller;
			int offset = input.path("offset").asInt();
			int end = Math.min(all.length, offset + input.path("limit").asInt());
			byte[] bytes = java.util.Arrays.copyOfRange(all, offset, end);
			var page = JSON.createObjectNode()
				.put("runId", input.path("runId").asText())
				.put("stream", input.path("stream").asText())
				.put("generation", input.path("stream").asText())
				.put("offset", offset)
				.put("bytes", java.util.Base64.getEncoder().encodeToString(bytes))
				.put("sha256", de.zorro909.skywright.backend.runlog.RunLogArchive.digest(bytes))
				.put("endOfFile", end == all.length)
				.put("sealed", terminal.get() && end == all.length)
				.put("finalSource", terminal.get())
				.set("cursor", JSON.createObjectNode());
			byte[] body = JSON.writeValueAsBytes(JSON.createObjectNode().put("schemaVersion", 1).set("page", page));
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
			requests.incrementAndGet();
		});
		server.start();
		System.setProperty("skywright.log-collector.endpoint", "http://127.0.0.1:" + server.getAddress().getPort());
		try (var storage = SeaweedFsFixture.start();
				var admin = S3AsyncClient.builder()
					.endpointOverride(storage.endpoint())
					.region(Region.US_EAST_1)
					.credentialsProvider(
							StaticCredentialsProvider.create(AwsBasicCredentials.create("test-key", "test-secret")))
					.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
					.build()) {
			storage.awaitReady(admin);
			storageBucket = "logs-" + UUID.randomUUID();
			storageEndpoint = storage.endpoint().toString();
			admin.createBucket(b -> b.bucket(storageBucket)).join();
			try (var backend = BackendFixture.startWith(Boundaries.class, "run-log-integration",
					"target-storage-integration")) {
				storageId = LocalRunAssemblyIT.register(backend, storage.endpoint(), storageBucket, "run-output",
						UUID.randomUUID());
				UUID run = create(backend);
				assertThat(backend.get("/api/v1/run-logs/" + UUID.randomUUID() + "/task").statusCode()).isEqualTo(404);
				assertThat(backend.get("/api/v1/run-logs/" + run + "/task?cursor=-1").statusCode()).isEqualTo(400);
				de.zorro909.skywright.backend.runlog.RunLogCaptureProbe.replacedProducerCannotPublish(
						backend.bean(de.zorro909.skywright.backend.runlog.RunLogCaptureStore.class), run,
						() -> CLOCK.advance(61));
				CLOCK.advance(6);
				SOURCE.jobs = List.of(job(run, "RUNNING"));
				lifecycle(backend, run);
				var archives = backend.bean(de.zorro909.skywright.backend.runlog.RunLogArchives.class);
				archives.reconcile(run);
				var sql = new org.springframework.jdbc.core.JdbcTemplate(backend.bean(javax.sql.DataSource.class));
				String checkpoint = sql.queryForObject(
						"SELECT checkpoint_json FROM skywright.run_log_capture WHERE run_id=?", String.class, run);
				assertThat(JSON.readTree(checkpoint).at("/task/bytes").asLong()).isEqualTo(1024 * 1024);
				// S3 index is durable but its database acknowledgement is lost.
				var reset = (tools.jackson.databind.node.ObjectNode) JSON.readTree(checkpoint);
				reset.set("task", JSON.readTree(
						JSON.writeValueAsBytes(de.zorro909.skywright.backend.runlog.RunLogArchive.Cursor.initial())));
				sql.update("UPDATE skywright.run_log_capture SET checkpoint_json=?,next_attempt_at=? WHERE run_id=?",
						reset.toString(), java.sql.Timestamp.from(CLOCK.now), run);
				backend.restart();
				archives = backend.bean(de.zorro909.skywright.backend.runlog.RunLogArchives.class);
				archives.reconcile(run);
				for (int i = 0; i < 3; i++) {
					CLOCK.advance(6);
					archives.reconcile(run);
				}
				var activePage = logPage(backend, run, "task", 0);
				assertThat(activePage.path("archiveState").asText()).isEqualTo("staging");
				assertThat(java.util.Base64.getDecoder().decode(activePage.path("bytesBase64").asText()))
					.isEqualTo(java.util.Arrays.copyOfRange(task, 0, 65536));
				assertFollow(backend, run, "task", task.length - 17, false,
						java.util.Arrays.copyOfRange(task, task.length - 17, task.length));
				terminal.set(true);
				SOURCE.jobs = List.of(job(run, "FAILED_SETUP"));
				lifecycle(backend, run);
				CLOCK.advance(6);
				archives.reconcile(run);
				var finalization = backend.bean(de.zorro909.skywright.backend.runlog.RunLogCaptureStore.class)
					.finalization(run);
				assertThat(finalization).isNotNull();
				String prefix = backend.bean(RunAcceptanceStore.class)
					.get(run)
					.definition()
					.value()
					.at("/trainingProjectVersion/projectIdentity")
					.asText() + "/" + run + "/v1/";
				byte[] manifestBytes = admin
					.getObject(b -> b.bucket(storageBucket).key(prefix + finalization.manifestKey()),
							software.amazon.awssdk.core.async.AsyncResponseTransformer.toBytes())
					.join()
					.asByteArray();
				assertThat(de.zorro909.skywright.backend.runlog.RunLogArchive.digest(manifestBytes))
					.isEqualTo(finalization.sha256());
				var manifest = JSON.readTree(manifestBytes);
				assertThat(manifest.at("/task/status").asText()).isEqualTo("complete");
				for (String stream : List.of("task", "controller")) {
					var captured = new java.io.ByteArrayOutputStream();
					long chunks = manifest.at("/" + stream + "/chunks").asLong();
					for (long i = 0; i < chunks; i++) {
						String key = prefix + "skypilot/logs/" + stream + "/index/%019d.json".formatted(i);
						var index = JSON.readTree(admin
							.getObject(b -> b.bucket(storageBucket).key(key),
									software.amazon.awssdk.core.async.AsyncResponseTransformer.toBytes())
							.join()
							.asByteArray());
						String raw = prefix + "skypilot/logs/" + stream
								+ "/chunks/%019d-".formatted(index.path("offset").asLong())
								+ index.path("sha256").asText();
						captured.writeBytes(admin
							.getObject(b -> b.bucket(storageBucket).key(raw),
									software.amazon.awssdk.core.async.AsyncResponseTransformer.toBytes())
							.join()
							.asByteArray());
					}
					assertThat(captured.toByteArray()).isEqualTo(stream.equals("task") ? task : controller);
				}
				int priorRequests = requests.get();
				server.stop(0);
				backend.restart();
				backend.bean(de.zorro909.skywright.backend.runlog.RunLogArchives.class).reconcile(run);
				assertThat(requests).hasValue(priorRequests);
				assertThat(
						backend.bean(de.zorro909.skywright.backend.runlog.RunLogCaptureStore.class).finalization(run))
					.isEqualTo(finalization);
				assertFollow(backend, run, "task", task.length - 17, true,
						java.util.Arrays.copyOfRange(task, task.length - 17, task.length));
				var controllerPage = logPage(backend, run, "controller", 0);
				assertThat(java.util.Base64.getDecoder().decode(controllerPage.path("bytesBase64").asText()))
					.isEqualTo(controller);
				var navigation = backend.get("/api/v1/run-logs/" + run + "/navigation");
				assertThat(navigation.statusCode()).as(navigation.body()).isEqualTo(200);
				assertThat(JSON.readTree(navigation.body()).path("items").get(0).path("kind").asText())
					.isEqualTo("setup");

				// Exercise the current-location seam; #53 owns the actual relocation
				// protocol.
				String movedBucket = "moved-logs-" + UUID.randomUUID();
				admin.createBucket(b -> b.bucket(movedBucket)).join();
				UUID movedStorage = LocalRunAssemblyIT.register(backend, storage.endpoint(), movedBucket, "run-output",
						UUID.randomUUID());
				var records = admin.listObjectsV2(b -> b.bucket(storageBucket).prefix(prefix)).join().contents();
				for (var record : records) {
					var bytes = admin
						.getObject(b -> b.bucket(storageBucket).key(record.key()),
								software.amazon.awssdk.core.async.AsyncResponseTransformer.toBytes())
						.join();
					admin
						.putObject(b -> b.bucket(movedBucket).key(record.key()).metadata(bytes.response().metadata()),
								software.amazon.awssdk.core.async.AsyncRequestBody.fromBytes(bytes.asByteArray()))
						.join();
				}
				var moved = (tools.jackson.databind.node.ObjectNode) backend.bean(RunAcceptanceStore.class)
					.currentStorage(run);
				moved.put("storageId", movedStorage.toString()).put("bucket", movedBucket);
				new org.springframework.jdbc.core.JdbcTemplate(backend.bean(javax.sql.DataSource.class)).update(
						"update skywright.run_store_location set storage_id=?, descriptor_json=? where run_id=?",
						movedStorage, moved.toString(), run);
				for (var record : records)
					admin.deleteObject(b -> b.bucket(storageBucket).key(record.key())).join();
				assertThat(logPage(backend, run, "controller", 0).path("bytesBase64").asText())
					.isEqualTo(controllerPage.path("bytesBase64").asText());
				assertFollow(backend, run, "task", task.length - 17, true,
						java.util.Arrays.copyOfRange(task, task.length - 17, task.length));
				var chunkKey = records.stream()
					.filter(r -> r.key().contains("/controller/chunks/"))
					.findFirst()
					.orElseThrow()
					.key();
				admin.deleteObject(b -> b.bucket(movedBucket).key(chunkKey)).join();
				assertThat(logPage(backend, run, "controller", 0).path("availability").asText())
					.isEqualTo("unavailable");
			}
		}
		finally {
			server.stop(0);
			System.clearProperty("skywright.log-collector.endpoint");
		}
	}

	private static JsonNode logPage(BackendFixture backend, UUID run, String stream, long cursor) throws Exception {
		var response = backend.get("/api/v1/run-logs/" + run + "/" + stream + "?cursor=" + cursor);
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		return JSON.readTree(response.body());
	}

	private static void assertFollow(BackendFixture backend, UUID run, String stream, long cursor, boolean finalized,
			byte[] expected) throws Exception {
		try (var client = java.net.http.HttpClient.newHttpClient()) {
			var response = client.send(java.net.http.HttpRequest
				.newBuilder(backend.baseUri()
					.resolve("/api/v1/run-logs/" + run + "/" + stream + "/follow?cursor=" + cursor))
				.timeout(java.time.Duration.ofSeconds(15))
				.GET()
				.build(), java.net.http.HttpResponse.BodyHandlers.ofInputStream());
			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
			try (var input = response.body()) {
				var frame = new java.io.ByteArrayOutputStream();
				int prior = -1;
				while (frame.size() < 128 * 1024) {
					int value = input.read();
					assertThat(value).as("SSE closed before its first complete event").isNotEqualTo(-1);
					frame.write(value);
					if (value == '\n' && prior == '\n')
						break;
					prior = value;
				}
				String text = frame.toString(java.nio.charset.StandardCharsets.UTF_8);
				String data = text.lines()
					.filter(line -> line.startsWith("data: "))
					.findFirst()
					.orElseThrow()
					.substring(6);
				var page = JSON.readTree(data);
				assertThat(page.path("archiveState").asText()).isEqualTo(finalized ? "finalized" : "staging");
				assertThat(page.path("fromCursor").asText()).isEqualTo(Long.toString(cursor));
				assertThat(java.util.Base64.getDecoder().decode(page.path("bytesBase64").asText())).isEqualTo(expected);
				if (finalized)
					assertThat(input.read()).as("finalization closes follow").isEqualTo(-1);
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
	@Profile("run-log-integration")
	@Import(TargetStorageIntegrationTestConfiguration.class)
	static class Boundaries {

		@Bean
		@Primary
		java.time.Clock archiveClock() {
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

}
