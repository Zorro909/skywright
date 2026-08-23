package de.zorro909.skywright.backend.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

	@TempDir
	Path temporaryDirectory;

	@Test
	void installedCommandPublishesOneLabeledMdsCorpusAtomically() throws Exception {
		try (var objectStorage = SeaweedFsFixture.start(); var administrator = administrator(objectStorage)) {
			objectStorage.awaitReady(administrator);
			String bucket = "dataset-publication-" + UUID.randomUUID();
			administrator.createBucket(CreateBucketRequest.builder().bucket(bucket).build()).join();
			Path corpus = this.corpus();
			Map<String, byte[]> localBefore = Map.of("index.json", Files.readAllBytes(corpus.resolve("index.json")),
					"shard.00000.mds", Files.readAllBytes(corpus.resolve("shard.00000.mds")));

			try (var backend = BackendFixture.startWithTargetStorageIntegration()) {
				var storage = backend.post("/api/v1/target-storages", registration(objectStorage.endpoint(), bucket));
				assertThat(storage.statusCode()).as(storage.body()).isEqualTo(201);
				String storageId = JSON.readTree(storage.body()).path("id").asText();
				var activated = backend.put("/api/v1/target-storages/" + storageId + "/activation",
						"{\"expectedRegistrationRevision\":2,\"activated\":true}");
				assertThat(activated.statusCode()).as(activated.body()).isEqualTo(200);

				var commandBuilder = new ProcessBuilder("uv", "run", "--project", "sdk", "--locked",
						"skywright-datasets", "publish", corpus.toString(), "--control-plane",
						backend.baseUri().toString(), "--target-storage", storageId, "--version-label", "release-1")
					.directory(Path.of(System.getProperty("repository.root")).toFile())
					.redirectErrorStream(false);
				commandBuilder.environment()
					.putAll(Map.of("AWS_ACCESS_KEY_ID", "test-key", "AWS_SECRET_ACCESS_KEY", "test-secret",
							"AWS_REGION", "us-east-1"));
				Process command = commandBuilder.start();
				String stdout = new String(command.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
				String stderr = new String(command.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
				assertThat(command.waitFor()).as(stderr).isZero();

				JsonNode result = JSON.readTree(stdout);
				assertThat(result.path("state").asText()).isEqualTo("committed");
				assertThat(result.path("versionLabel").asText()).isEqualTo("release-1");
				assertThat(result.path("formatIdentity").asText()).isEqualTo("mosaicml-streaming-mds@2");
				assertThat(result.path("manifestIdentity").asText()).startsWith("sha256:");
				assertThat(result.path("contentFingerprint").asText()).startsWith("sha256:");
				assertThat(result.path("verifiedObjectCount").asLong()).isEqualTo(2);
				assertThat(result.path("verifiedByteCount").asLong()).isPositive();
				assertThat(result.path("preferredDefinitionId").asText())
					.isEqualTo(result.path("definitionId").asText());
				assertThat(result.path("preferredDefinitionChanged").asBoolean()).isTrue();
				assertThat(result.path("verificationWorkerPid").asLong()).isPositive()
					.isNotEqualTo(ProcessHandle.current().pid());

				var lineage = backend.get("/api/v1/datasets/" + result.path("datasetId").asText());
				var catalog = backend.get("/api/v1/dataset-catalog/" + result.path("definitionId").asText());
				assertThat(lineage.statusCode()).as(lineage.body()).isEqualTo(200);
				assertThat(lineage.body()).contains(result.path("definitionId").asText());
				assertThat(catalog.statusCode()).as(catalog.body()).isEqualTo(200);
				assertThat(catalog.body()).contains("release-1", "mosaicml-streaming-mds@2", "authority",
						result.path("payloadLocation").asText());

				Set<String> keys = administrator.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build())
					.join()
					.contents()
					.stream()
					.map(value -> value.key())
					.collect(Collectors.toSet());
				String payload = result.path("payloadLocation").asText();
				String operation = result.path("operationLocation").asText();
				assertThat(keys).containsExactlyInAnyOrder(payload + "/index.json", payload + "/shard.00000.mds",
						operation + "/manifest.json");
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

	private Path corpus() throws Exception {
		Path corpus = Files.createDirectory(this.temporaryDirectory.resolve("corpus"));
		byte[] shard = mdsShard("one installed-command sample".getBytes(StandardCharsets.UTF_8));
		Files.write(corpus.resolve("shard.00000.mds"), shard);
		Files.writeString(corpus.resolve("index.json"),
				"""
						{"version":2,"shards":[{"column_encodings":["bytes"],"column_names":["value"],"column_sizes":[null],"compression":null,"format":"mds","hashes":[],"raw_data":{"basename":"shard.00000.mds","bytes":%d,"hashes":{}},"samples":1,"size_limit":1024,"version":2,"zip_data":null}]}
						"""
					.formatted(shard.length)
					.strip(),
				StandardCharsets.UTF_8);
		return corpus;
	}

	private static byte[] mdsShard(byte[] value) {
		byte[] configuration = """
				{"column_encodings":["bytes"],"column_names":["value"],"column_sizes":[null],"compression":null,"format":"mds","hashes":[],"size_limit":1024,"version":2}
				"""
			.strip()
			.getBytes(StandardCharsets.UTF_8);
		int firstOffset = 12 + configuration.length;
		return ByteBuffer.allocate(firstOffset + 4 + value.length)
			.order(ByteOrder.LITTLE_ENDIAN)
			.putInt(1)
			.putInt(firstOffset)
			.putInt(firstOffset + 4 + value.length)
			.put(configuration)
			.putInt(value.length)
			.put(value)
			.array();
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
