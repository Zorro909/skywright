package de.zorro909.skywright.backend.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import de.zorro909.skywright.backend.datasetpublication.DatasetPublicationCommitGateTestConfiguration;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
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
	void existingDatasetPublicationRequiresAnExplicitPreferredDefinitionDecision() throws Exception {
		try (var backend = BackendFixture.start()) {
			var response = backend.post("/api/v1/dataset-publications", """
					{
					  "targetStorageId":"%s",
					  "datasetId":"%s",
					  "expectedDatasetRevision":1,
					  "formatIdentity":"mosaicml-streaming-mds@2",
					  "manifestIdentity":"sha256:%s",
					  "contentFingerprint":"sha256:%s",
					  "objectCount":1,
					  "byteCount":1
					}
					""".formatted(UUID.randomUUID(), UUID.randomUUID(), "0".repeat(64), "1".repeat(64)));

			assertThat(response.statusCode()).as(response.body()).isEqualTo(422);
			assertThat(response.body()).contains("SKYWRIGHT_DATASET_PUBLICATION_INVALID");
		}
	}

	@Test
	void publicationResumeRejectsChangedFactsAndRetainsSafeProgress() throws Exception {
		try (var objectStorage = SeaweedFsFixture.start(); var administrator = administrator(objectStorage)) {
			objectStorage.awaitReady(administrator);
			String bucket = "dataset-publication-resume-" + UUID.randomUUID();
			administrator.createBucket(CreateBucketRequest.builder().bucket(bucket).build()).join();
			try (var backend = BackendFixture.startWithTargetStorageIntegration()) {
				var storage = backend.post("/api/v1/target-storages", registration(objectStorage.endpoint(), bucket));
				String storageId = JSON.readTree(storage.body()).path("id").asText();
				backend.put("/api/v1/target-storages/" + storageId + "/activation",
						"{\"expectedRegistrationRevision\":2,\"activated\":true}");
				String request = """
						{
						  "targetStorageId":"%s",
						  "versionLabel":"resume-v1",
						  "formatIdentity":"mosaicml-streaming-mds@2",
						  "manifestIdentity":"sha256:%s",
						  "contentFingerprint":"sha256:%s",
						  "objectCount":3,
						  "byteCount":21
						}
						""".formatted(storageId, "1".repeat(64), "2".repeat(64));
				var initiatedResponse = backend.post("/api/v1/dataset-publications", request);
				assertThat(initiatedResponse.statusCode()).as(initiatedResponse.body()).isEqualTo(201);
				JsonNode initiated = JSON.readTree(initiatedResponse.body());
				String publicationId = initiated.path("publicationId").asText();
				assertThat(initiated.path("uploadedObjectCount").asLong()).isZero();
				assertThat(initiated.path("uploadedByteCount").asLong()).isZero();
				assertThat(initiated.path("expectedDatasetRevision").isNull()).isTrue();
				assertThat(initiated.path("preferredDefinitionDecision").isNull()).isTrue();
				assertThat(initiated.path("retryGuidance").asText()).contains("resume", publicationId);

				var resumed = backend.post("/api/v1/dataset-publications/" + publicationId + "/resume", request);
				assertThat(resumed.statusCode()).as(resumed.body()).isEqualTo(200);
				assertThat(JSON.readTree(resumed.body()).path("datasetId")).isEqualTo(initiated.path("datasetId"));
				var changed = backend.post("/api/v1/dataset-publications/" + publicationId + "/resume",
						request.replace("resume-v1", "changed"));
				assertThat(changed.statusCode()).as(changed.body()).isEqualTo(409);
				assertThat(changed.body()).contains("SKYWRIGHT_DATASET_PUBLICATION_CONFLICT");
				var changedDataset = backend.post("/api/v1/dataset-publications/" + publicationId + "/resume",
						request.replace("\"targetStorageId\"",
								"\"datasetId\":\"00000000-0000-0000-0000-000000000099\","
										+ "\"expectedDatasetRevision\":1,\"preferredDefinitionDecision\":\"advance\","
										+ "\"targetStorageId\""));
				assertThat(changedDataset.statusCode()).as(changedDataset.body()).isEqualTo(409);
				var changedPreference = backend
					.post("/api/v1/dataset-publications/" + publicationId + "/resume", request.replace(
							"\"targetStorageId\"",
							"\"datasetId\":\"00000000-0000-0000-0000-000000000099\","
									+ "\"expectedDatasetRevision\":1,\"preferredDefinitionDecision\":\"keep\","
									+ "\"targetStorageId\""));
				assertThat(changedPreference.statusCode()).as(changedPreference.body()).isEqualTo(409);

				var progress = backend.put("/api/v1/dataset-publications/" + publicationId + "/progress",
						"{\"uploadedObjectCount\":2,\"uploadedByteCount\":13}");
				assertThat(progress.statusCode()).as(progress.body()).isEqualTo(200);
				var failure = backend.put("/api/v1/dataset-publications/" + publicationId + "/failure", """
						{
						  "failureCode":"DATASET_UPLOAD_FAILED"
						}
						""");
				assertThat(failure.statusCode()).as(failure.body()).isEqualTo(200);
				JsonNode inspected = JSON.readTree(backend.get("/api/v1/dataset-publications/" + publicationId).body());
				assertThat(inspected.path("state").asText()).isEqualTo("failed");
				assertThat(inspected.path("uploadedObjectCount").asLong()).isEqualTo(2);
				assertThat(inspected.path("uploadedByteCount").asLong()).isEqualTo(13);
				assertThat(inspected.path("failureDetail").asText()).contains("temporarily unavailable")
					.doesNotContain("Exception", "/tmp", "secret");
				assertThat(inspected.path("unavailableSource").asText()).isEqualTo("Dataset Target Storage");
				assertThat(inspected.path("retryGuidance").asText()).contains("--resume", publicationId);
				assertThat(inspected.path("updatedAt").asText()).isNotBlank();
			}
		}
	}

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

				assertInvalidCorpusInvisible(backend, storageId, localBefore, "unreferenced",
						path -> Files.writeString(path.resolve("unreferenced.bin"), "not indexed"),
						"SKYWRIGHT_DATASET_CORPUS_FILE_UNREFERENCED");
				assertInvalidCorpusInvisible(backend, storageId, localBefore, "version",
						path -> replaceIndex(path, "\"version\":2", "\"version\":1"),
						"SKYWRIGHT_DATASET_MDS_VERSION_UNSUPPORTED");
				assertInvalidCorpusInvisible(backend, storageId, localBefore, "format",
						path -> replaceIndex(path, "\"format\":\"mds\"", "\"format\":\"json\""),
						"SKYWRIGHT_DATASET_STREAMING_FORMAT_UNSUPPORTED");
				assertInvalidCorpusInvisible(backend, storageId, localBefore, "encoding",
						path -> replaceIndex(path, "\"column_encodings\":[\"str\"]", "\"column_encodings\":[\"pkl\"]"),
						"SKYWRIGHT_DATASET_CORPUS_UNSAFE_ENCODING");
				assertInvalidCorpusInvisible(backend, storageId, localBefore, "path",
						path -> replaceIndex(path, "shard.00000.mds", "../shard.00000.mds"),
						"SKYWRIGHT_DATASET_CORPUS_PATH_INVALID");
				assertInvalidCorpusInvisible(backend, storageId, localBefore, "metadata",
						path -> replaceIndex(path, "\"column_sizes\":[null]", "\"column_sizes\":[]"),
						"SKYWRIGHT_DATASET_MDS_DECODING_METADATA_INVALID");
				assertInvalidCorpusInvisible(backend, storageId, localBefore, "sample-count",
						path -> replaceIndex(path, "\"samples\":1", "\"samples\":2"),
						"SKYWRIGHT_DATASET_MDS_SAMPLE_METADATA_MISMATCH");
				assertInvalidCorpusInvisible(backend, storageId, localBefore, "missing",
						path -> Files.delete(firstPayload(path)), "SKYWRIGHT_DATASET_CORPUS_FILE_MISSING");

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
				assertThat(result.path("uploadedObjectCount").asLong())
					.isEqualTo(expected.path("objectCount").asLong());
				assertThat(result.path("uploadedByteCount").asLong()).isEqualTo(expected.path("byteCount").asLong());
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

				String fingerprint = result.path("contentFingerprint").asText();
				char differentDigit = fingerprint.charAt(23) == '0' ? '1' : '0';
				String collidingFingerprint = fingerprint.substring(0, 23) + differentDigit + fingerprint.substring(24);
				var collidingInitiation = backend.post("/api/v1/dataset-publications", """
						{
						  "targetStorageId":"%s",
						  "datasetId":"%s",
						  "expectedDatasetRevision":1,
						  "preferredDefinitionDecision":"advance",
						  "formatIdentity":"mosaicml-streaming-mds@2",
						  "manifestIdentity":"%s",
						  "contentFingerprint":"%s",
						  "objectCount":1,
						  "byteCount":1
						}
						""".formatted(storageId, result.path("datasetId").asText(),
						result.path("manifestIdentity").asText(), collidingFingerprint));
				assertThat(collidingInitiation.statusCode()).as(collidingInitiation.body()).isEqualTo(201);
				JsonNode colliding = JSON.readTree(collidingInitiation.body());
				assertThat(colliding.path("versionLabel").asText()).isEqualTo(collidingFingerprint.substring(7, 25));
				assertThat(colliding.path("contentFingerprint").asText()).isEqualTo(collidingFingerprint);
				var repeatedFingerprintInitiation = backend.post("/api/v1/dataset-publications", """
						{
						  "targetStorageId":"%s",
						  "datasetId":"%s",
						  "expectedDatasetRevision":1,
						  "preferredDefinitionDecision":"keep",
						  "formatIdentity":"mosaicml-streaming-mds@2",
						  "manifestIdentity":"%s",
						  "contentFingerprint":"%s",
						  "objectCount":1,
						  "byteCount":1
						}
						""".formatted(storageId, result.path("datasetId").asText(),
						result.path("manifestIdentity").asText(), fingerprint));
				assertThat(repeatedFingerprintInitiation.statusCode()).as(repeatedFingerprintInitiation.body())
					.isEqualTo(201);
				assertThat(JSON.readTree(repeatedFingerprintInitiation.body()).path("versionLabel").asText())
					.isEqualTo(fingerprint.substring(7, 25));

				CommandResult separateLineageCommand = runCommand(corpus, backend.baseUri(), storageId,
						"--version-label", "same-content-new-lineage");
				assertThat(separateLineageCommand.exitCode()).as(separateLineageCommand.stderr()).isZero();
				JsonNode separateLineage = JSON.readTree(separateLineageCommand.stdout());
				assertThat(separateLineage.path("datasetId")).isNotEqualTo(result.path("datasetId"));
				assertThat(separateLineage.path("contentFingerprint")).isEqualTo(result.path("contentFingerprint"));

				CommandResult advancedCommand = runCommand(corpus, backend.baseUri(), storageId, "--version-label",
						"v2", "--dataset", result.path("datasetId").asText(), "--expected-dataset-revision", "1",
						"--advance-preferred");
				assertThat(advancedCommand.exitCode()).as(advancedCommand.stderr()).isZero();
				JsonNode advanced = JSON.readTree(advancedCommand.stdout());
				assertThat(advanced.path("datasetId")).isEqualTo(result.path("datasetId"));
				assertThat(advanced.path("definitionId")).isNotEqualTo(result.path("definitionId"));
				assertThat(advanced.path("preferredDefinitionId")).isEqualTo(advanced.path("definitionId"));
				assertThat(advanced.path("preferredDefinitionChanged").asBoolean()).isTrue();

				var repeatedCompletion = backend.post(
						"/api/v1/dataset-publications/" + advanced.path("publicationId").asText() + "/completion",
						"{}");
				assertThat(repeatedCompletion.statusCode()).as(repeatedCompletion.body()).isEqualTo(202);
				assertThat(JSON.readTree(repeatedCompletion.body()).path("definitionId"))
					.isEqualTo(advanced.path("definitionId"));

				CommandResult keptCommand = runCommand(corpus, backend.baseUri(), storageId, "--version-label", "v3",
						"--dataset", result.path("datasetId").asText(), "--expected-dataset-revision", "2",
						"--keep-preferred");
				assertThat(keptCommand.exitCode()).as(keptCommand.stderr()).isZero();
				JsonNode kept = JSON.readTree(keptCommand.stdout());
				assertThat(kept.path("preferredDefinitionId")).isEqualTo(advanced.path("definitionId"));
				assertThat(kept.path("preferredDefinitionChanged").asBoolean()).isFalse();

				CommandResult duplicateLabel = runCommand(corpus, backend.baseUri(), storageId, "--version-label", "v2",
						"--dataset", result.path("datasetId").asText(), "--expected-dataset-revision", "3",
						"--keep-preferred");
				assertThat(duplicateLabel.exitCode()).isEqualTo(1);
				assertThat(duplicateLabel.stderr()).contains("DATASET_VERSION_LABEL_CONFLICT");

				CommandResult staleCommand = runCommand(corpus, backend.baseUri(), storageId, "--version-label",
						"stale", "--dataset", result.path("datasetId").asText(), "--expected-dataset-revision", "2",
						"--advance-preferred");
				assertThat(staleCommand.exitCode()).isEqualTo(1);
				assertThat(staleCommand.stderr()).contains("DATASET_REVISION_STALE");

				var updatedLineage = backend.get("/api/v1/datasets/" + result.path("datasetId").asText());
				assertThat(updatedLineage.statusCode()).as(updatedLineage.body()).isEqualTo(200);
				assertThat(updatedLineage.body()).contains("\"revision\":3", advanced.path("definitionId").asText());

				try (var peer = backend.peerWithTargetStorageIntegration();
						var requests = Executors.newVirtualThreadPerTaskExecutor()) {
					DatasetPublicationCommitGateTestConfiguration.blockNextCommits(2);
					var first = requests.submit(() -> runCommand(corpus, backend.baseUri(), storageId,
							"--version-label", "competing-a", "--dataset", result.path("datasetId").asText(),
							"--expected-dataset-revision", "3", "--advance-preferred"));
					var second = requests.submit(() -> runCommand(corpus, peer.baseUri(), storageId, "--version-label",
							"competing-b", "--dataset", result.path("datasetId").asText(),
							"--expected-dataset-revision", "3", "--advance-preferred"));
					var competing = java.util.List.of(first.get(), second.get())
						.stream()
						.sorted(Comparator.comparingInt(CommandResult::exitCode))
						.toList();
					assertThat(competing).extracting(CommandResult::exitCode).containsExactly(0, 1);
					assertThat(competing.get(1).stderr()).contains("DATASET_REVISION_STALE");
					JsonNode winner = JSON.readTree(competing.getFirst().stdout());
					var competedLineage = backend.get("/api/v1/datasets/" + result.path("datasetId").asText());
					assertThat(competedLineage.body()).contains("\"revision\":4", winner.path("definitionId").asText());

					DatasetPublicationCommitGateTestConfiguration.blockNextCommits(2);
					var advancing = requests.submit(() -> runCommand(corpus, backend.baseUri(), storageId,
							"--version-label", "pointer-race-advance", "--dataset", result.path("datasetId").asText(),
							"--expected-dataset-revision", "4", "--advance-preferred"));
					var keeping = requests.submit(() -> runCommand(corpus, peer.baseUri(), storageId, "--version-label",
							"pointer-race-keep", "--dataset", result.path("datasetId").asText(),
							"--expected-dataset-revision", "4", "--keep-preferred"));
					var pointerRace = java.util.List.of(advancing.get(), keeping.get())
						.stream()
						.sorted(Comparator.comparingInt(CommandResult::exitCode))
						.toList();
					assertThat(pointerRace).extracting(CommandResult::exitCode).containsExactly(0, 1);
					assertThat(pointerRace.get(1).stderr()).contains("DATASET_REVISION_STALE");
					assertThat(backend.get("/api/v1/datasets/" + result.path("datasetId").asText()).body())
						.contains("\"revision\":5");
				}
				var versions = backend
					.get("/api/v1/dataset-catalog?datasetId=" + result.path("datasetId").asText() + "&limit=100");
				assertThat(versions.statusCode()).as(versions.body()).isEqualTo(200);
				assertThat(versions.body())
					.contains(result.path("definitionId").asText(), advanced.path("definitionId").asText(),
							kept.path("definitionId").asText())
					.doesNotContain("\"versionLabel\":\"stale\"");

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
				assertThat(keys).containsAll(expectedKeys);
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

				try (var restarted = backend.restartWithTargetStorageIntegration()) {
					var persistedLineage = restarted.get("/api/v1/datasets/" + result.path("datasetId").asText());
					assertThat(persistedLineage.statusCode()).as(persistedLineage.body()).isEqualTo(200);
					assertThat(persistedLineage.body()).contains("\"revision\":5");
					var persistedVersions = restarted
						.get("/api/v1/dataset-catalog?datasetId=" + result.path("datasetId").asText() + "&limit=100");
					assertThat(JSON.readTree(persistedVersions.body()).path("items")).hasSize(5);
					var persistedRetry = restarted.post(
							"/api/v1/dataset-publications/" + result.path("publicationId").asText() + "/completion",
							"{}");
					assertThat(persistedRetry.statusCode()).as(persistedRetry.body()).isEqualTo(202);
					assertThat(JSON.readTree(persistedRetry.body()).path("definitionId"))
						.isEqualTo(result.path("definitionId"));
				}
			}
		}
	}

	private void assertInvalidCorpusInvisible(BackendFixture backend, String storageId, Map<String, byte[]> files,
			String name, CorpusMutation mutation, String expectedCode) throws Exception {
		Path invalid = Files.createDirectory(this.temporaryDirectory.resolve("invalid-" + name));
		for (var entry : files.entrySet()) {
			Path destination = invalid.resolve(entry.getKey());
			Files.createDirectories(destination.getParent());
			Files.write(destination, entry.getValue());
		}
		mutation.apply(invalid);
		CommandResult command = runCommand(invalid, backend.baseUri(), storageId);
		assertThat(command.exitCode()).as(command.stderr()).isEqualTo(2);
		assertThat(command.stderr()).contains(expectedCode);
		var catalog = backend.get("/api/v1/dataset-catalog");
		assertThat(catalog.statusCode()).as(catalog.body()).isEqualTo(200);
		assertThat(JSON.readTree(catalog.body()).path("items")).isEmpty();
	}

	private static void replaceIndex(Path corpus, String before, String after) throws Exception {
		Path index = corpus.resolve("index.json");
		String original = Files.readString(index);
		String replaced = original.replaceFirst(java.util.regex.Pattern.quote(before),
				java.util.regex.Matcher.quoteReplacement(after));
		assertThat(replaced).isNotEqualTo(original);
		Files.writeString(index, replaced);
	}

	private static Path firstPayload(Path corpus) throws Exception {
		try (var files = Files.walk(corpus)) {
			return files.filter(Files::isRegularFile)
				.filter(path -> !path.getFileName().toString().equals("index.json"))
				.findFirst()
				.orElseThrow();
		}
	}

	@FunctionalInterface
	private interface CorpusMutation {

		void apply(Path corpus) throws Exception;

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

	private static CommandResult runCommand(Path corpus, URI controlPlane, String storageId, String... extra)
			throws Exception {
		var arguments = new java.util.ArrayList<>(
				java.util.List.of("uv", "run", "--project", "sdk", "--locked", "skywright-datasets", "publish",
						corpus.toString(), "--control-plane", controlPlane.toString(), "--target-storage", storageId));
		arguments.addAll(java.util.List.of(extra));
		var commandBuilder = new ProcessBuilder(arguments)
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
