package de.zorro909.skywright.backend.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("real-service")
final class DatasetPublicationApiIT {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final UUID TRANSFER_WORKER_BINDING = UUID.fromString("00000000-0000-0000-0000-000000000003");

	@TempDir
	Path temporaryDirectory;

	@Test
	void installedCommandPublishesTheVersionedMdsContractFixtureAtomically() throws Exception {
		try (var objectStorage = SeaweedFsFixture.start(); var administrator = administrator(objectStorage)) {
			objectStorage.awaitReady(administrator);
			String bucket = "dataset-publication-" + UUID.randomUUID();
			administrator.createBucket(CreateBucketRequest.builder().bucket(bucket).build()).join();
			JsonNode fixture = JSON.readTree(Path.of(System.getProperty("repository.root"), "tests", "fixtures",
					"dataset-publication", "mds-v2-contract.json")
				.toFile());
			Path corpus = Files.createDirectory(this.temporaryDirectory.resolve("corpus"));
			Map<String, byte[]> localBefore = new HashMap<>();
			for (JsonNode file : fixture.path("files")) {
				String objectKey = file.path("objectKey").asText();
				byte[] bytes = Base64.getDecoder().decode(file.path("base64").asText());
				Path destination = corpus.resolve(objectKey);
				Files.createDirectories(destination.getParent());
				Files.write(destination, bytes);
				localBefore.put(objectKey, bytes);
			}
			JsonNode expected = fixture.path("expected");

			try (var backend = BackendFixture.startWithTargetStorageIntegration()) {
				var storage = backend.post("/api/v1/target-storages", registration(objectStorage.endpoint(), bucket));
				assertThat(storage.statusCode()).as(storage.body()).isEqualTo(201);
				String storageId = JSON.readTree(storage.body()).path("id").asText();
				var activated = backend.put("/api/v1/target-storages/" + storageId + "/activation",
						"{\"expectedRegistrationRevision\":2,\"activated\":true}");
				assertThat(activated.statusCode()).as(activated.body()).isEqualTo(200);

				Path extra = corpus.resolve("unreferenced.bin");
				Files.writeString(extra, "must fail before initiation", StandardCharsets.UTF_8);
				CommandResult invalid = runCommand(corpus, backend.baseUri(), storageId);
				assertThat(invalid.exitCode()).as(invalid.stderr()).isEqualTo(2);
				assertThat(invalid.stderr()).contains("SKYWRIGHT_DATASET_CORPUS_FILE_UNREFERENCED");
				var emptyCatalog = backend.get("/api/v1/dataset-catalog");
				assertThat(emptyCatalog.statusCode()).as(emptyCatalog.body()).isEqualTo(200);
				assertThat(JSON.readTree(emptyCatalog.body()).path("items")).isEmpty();
				Files.delete(extra);

				CommandResult command = runCommand(corpus, backend.baseUri(), storageId);
				assertThat(command.exitCode()).as(command.stderr()).isZero();

				JsonNode result = JSON.readTree(command.stdout());
				assertThat(result.path("state").asText()).isEqualTo("committed");
				assertThat(result.path("versionLabel").asText())
					.isEqualTo(expected.path("contentFingerprint").asText().substring(7, 23));
				assertThat(result.path("formatIdentity").asText()).isEqualTo(expected.path("formatIdentity").asText());
				assertThat(result.path("manifestIdentity").asText())
					.isEqualTo(expected.path("manifestIdentity").asText());
				assertThat(result.path("contentFingerprint").asText())
					.isEqualTo(expected.path("contentFingerprint").asText());
				assertThat(result.path("verifiedObjectCount").asLong())
					.isEqualTo(expected.path("objectCount").asLong());
				assertThat(result.path("verifiedByteCount").asLong()).isEqualTo(expected.path("byteCount").asLong());
				assertThat(result.path("preferredDefinitionId").asText())
					.isEqualTo(result.path("definitionId").asText());
				assertThat(result.path("preferredDefinitionChanged").asBoolean()).isTrue();
				assertThat(result.path("verificationWorkerPid").asLong()).isPositive()
					.isNotEqualTo(ProcessHandle.current().pid());
				assertThat(backend.countReleasedCredentialProjections(
						UUID.fromString(result.path("publicationId").asText()), TRANSFER_WORKER_BINDING, 1))
					.isOne();

				var lineage = backend.get("/api/v1/datasets/" + result.path("datasetId").asText());
				var catalog = backend.get("/api/v1/dataset-catalog/" + result.path("definitionId").asText());
				assertThat(lineage.statusCode()).as(lineage.body()).isEqualTo(200);
				assertThat(lineage.body()).contains(result.path("definitionId").asText());
				assertThat(catalog.statusCode()).as(catalog.body()).isEqualTo(200);
				assertThat(catalog.body()).contains(result.path("versionLabel").asText(), "mosaicml-streaming-mds@2",
						"authority", result.path("payloadLocation").asText());

				Set<String> keys = administrator.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build())
					.join()
					.contents()
					.stream()
					.map(value -> value.key())
					.collect(Collectors.toSet());
				String payload = result.path("payloadLocation").asText();
				String operation = result.path("operationLocation").asText();
				Set<String> expectedKeys = new HashSet<>();
				localBefore.keySet().forEach(key -> expectedKeys.add(payload + "/" + key));
				expectedKeys.add(operation + "/manifest.json");
				assertThat(keys).containsExactlyInAnyOrderElementsOf(expectedKeys);
				for (var entry : localBefore.entrySet()) {
					byte[] remote = administrator
						.getObject(
								GetObjectRequest.builder().bucket(bucket).key(payload + "/" + entry.getKey()).build(),
								AsyncResponseTransformer.toBytes())
						.join()
						.asByteArray();
					assertThat(remote).isEqualTo(entry.getValue());
					assertThat(Files.readAllBytes(corpus.resolve(entry.getKey()))).isEqualTo(entry.getValue());
				}

				var failedInitiation = backend.post("/api/v1/dataset-publications", """
						{
						  "targetStorageId":"%s",
						  "versionLabel":"missing-payload",
						  "formatIdentity":"mosaicml-streaming-mds@2",
						  "manifestIdentity":"sha256:%s",
						  "contentFingerprint":"sha256:%s",
						  "objectCount":1,
						  "byteCount":1
						}
						""".formatted(storageId, "0".repeat(64), "1".repeat(64)));
				assertThat(failedInitiation.statusCode()).as(failedInitiation.body()).isEqualTo(201);
				JsonNode failed = JSON.readTree(failedInitiation.body());
				var failedCompletion = backend.post(
						"/api/v1/dataset-publications/" + failed.path("publicationId").asText() + "/completion", "{}");
				assertThat(failedCompletion.statusCode()).as(failedCompletion.body()).isEqualTo(202);
				JsonNode failedResult = awaitTerminalPublication(backend, failed.path("publicationId").asText());
				assertThat(backend.get("/api/v1/datasets/" + failed.path("datasetId").asText()).statusCode())
					.isEqualTo(404);
				assertThat(backend.get("/api/v1/dataset-catalog/" + failed.path("definitionId").asText()).statusCode())
					.isEqualTo(404);
				var failedOperation = backend
					.get("/api/v1/dataset-publications/" + failed.path("publicationId").asText());
				assertThat(failedOperation.statusCode()).as(failedOperation.body()).isEqualTo(200);
				assertThat(failedResult.toString()).contains("\"state\":\"failed\"",
						"\"failureCode\":\"DATASET_VERIFICATION_UNAVAILABLE\"", "\"retryable\":true");
			}
		}
	}

	private static JsonNode awaitTerminalPublication(BackendFixture backend, String publicationId) throws Exception {
		for (int attempt = 0; attempt < 200; attempt++) {
			var response = backend.get("/api/v1/dataset-publications/" + publicationId);
			JsonNode publication = JSON.readTree(response.body());
			if (!publication.path("state").asText().equals("verifying")) {
				return publication;
			}
			Thread.sleep(50);
		}
		throw new AssertionError("Dataset Publication did not reach a terminal state");
	}

	private static CommandResult runCommand(Path corpus, URI controlPlane, String storageId) throws Exception {
		var commandBuilder = new ProcessBuilder("uv", "run", "--project", "sdk", "--locked", "skywright-datasets",
				"publish", corpus.toString(), "--control-plane", controlPlane.toString(), "--target-storage", storageId)
			.directory(Path.of(System.getProperty("repository.root")).toFile())
			.redirectErrorStream(false);
		commandBuilder.environment()
			.putAll(Map.of("AWS_ACCESS_KEY_ID", "test-key", "AWS_SECRET_ACCESS_KEY", "test-secret", "AWS_REGION",
					"us-east-1"));
		Process command = commandBuilder.start();
		String stdout = new String(command.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		String stderr = new String(command.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
		return new CommandResult(command.waitFor(), stdout, stderr);
	}

	private record CommandResult(int exitCode, String stdout, String stderr) {
	}

	private static S3AsyncClient administrator(SeaweedFsFixture storage) {
		return S3AsyncClient.builder()
			.httpClientBuilder(NettyNioAsyncHttpClient.builder())
			.endpointOverride(storage.endpoint())
			.region(Region.US_EAST_1)
			.credentialsProvider(
					StaticCredentialsProvider.create(AwsBasicCredentials.create("test-key", "test-secret")))
			.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
			.build();
	}

	private static String registration(URI endpoint, String bucket) {
		return """
				{
				  "name": "Dataset publication authority",
				  "purpose": "dataset",
				  "bucket": "%s",
				  "configuration": {
				    "endpoint": "%s",
				    "region": "us-east-1",
				    "pathStyleAccess": true,
				    "compatibilityOptions": {"chunkedEncoding":"disabled"}
				  },
				  "bindings": [
				    {"role":"training-process","bindingId":"00000000-0000-0000-0000-000000000001","bindingRevision":1},
				    {"role":"backend","bindingId":"00000000-0000-0000-0000-000000000002","bindingRevision":1},
				    {"role":"transfer-worker","bindingId":"00000000-0000-0000-0000-000000000003","bindingRevision":1}
				  ]
				}
				""".formatted(bucket, endpoint);
	}

}
