package de.zorro909.skywright.backend.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import de.zorro909.skywright.backend.datasetpublication.DatasetPublicationCommitGateTestConfiguration;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("real-service")
final class DatasetPublicationApiIT {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final UUID TRANSFER_WORKER_BINDING = UUID.fromString("00000000-0000-0000-0000-000000000003");

	@TempDir
	Path temporaryDirectory;

	@Test
	void publicationCanBeAbortedBeforeUploadAndReportsVerifiedAbsence() throws Exception {
		try (var objectStorage = SeaweedFsFixture.start(); var administrator = administrator(objectStorage)) {
			objectStorage.awaitReady(administrator);
			String bucket = "dataset-publication-abort-" + UUID.randomUUID();
			administrator.createBucket(CreateBucketRequest.builder().bucket(bucket).build()).join();
			try (var backend = BackendFixture.startWithTargetStorageIntegration()) {
				var storage = backend.post("/api/v1/target-storages", registration(objectStorage.endpoint(), bucket));
				String storageId = JSON.readTree(storage.body()).path("id").asText();
				backend.put("/api/v1/target-storages/" + storageId + "/activation",
						"{\"expectedRegistrationRevision\":2,\"activated\":true}");
				JsonNode publication = JSON.readTree(backend.post("/api/v1/dataset-publications", """
						{
						  "targetStorageId":"%s",
						  "formatIdentity":"mosaicml-streaming-mds@2",
						  "manifestIdentity":"sha256:%s",
						  "contentFingerprint":"sha256:%s",
						  "objectCount":1,
						  "byteCount":1
						}
						""".formatted(storageId, "0".repeat(64), "1".repeat(64))).body());

				var accepted = backend.post(
						"/api/v1/dataset-publications/" + publication.path("publicationId").asText() + "/abort", "{}");
				assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
				assertThat(JSON.readTree(accepted.body()).path("state").asText()).isIn("aborting", "aborted");

				JsonNode aborted = awaitPublicationState(backend, publication.path("publicationId").asText(),
						"aborted");
				assertThat(aborted.path("publicationId")).isEqualTo(publication.path("publicationId"));
				assertThat(aborted.path("completedAt").asText()).isNotBlank();
				assertThat(aborted.path("retryable").asBoolean()).isFalse();

				JsonNode failedVerification = JSON.readTree(backend.post("/api/v1/dataset-publications", """
						{
						  "targetStorageId":"%s",
						  "formatIdentity":"mosaicml-streaming-mds@2",
						  "manifestIdentity":"sha256:%s",
						  "contentFingerprint":"sha256:%s",
						  "objectCount":1,
						  "byteCount":1
						}
						""".formatted(storageId, "2".repeat(64), "3".repeat(64))).body());
				String failedPublicationId = failedVerification.path("publicationId").asText();
				backend.post("/api/v1/dataset-publications/" + failedPublicationId + "/completion", "{}");
				assertThat(awaitTerminalPublication(backend, failedPublicationId).path("state").asText())
					.isEqualTo("failed");
				var abortFailed = backend.post("/api/v1/dataset-publications/" + failedPublicationId + "/abort", "{}");
				assertThat(abortFailed.statusCode()).as(abortFailed.body()).isEqualTo(202);
				assertThat(
						awaitPublicationState(backend, failedPublicationId, "aborted").path("publicationId").asText())
					.isEqualTo(failedPublicationId);
			}
		}
	}

	@Test
	void abortWaitsForAnActiveTransferBeforeReportingVerifiedAbsence() throws Exception {
		try (var objectStorage = SeaweedFsFixture.start(); var administrator = administrator(objectStorage)) {
			objectStorage.awaitReady(administrator);
			String bucket = "active-transfer-" + UUID.randomUUID();
			administrator.createBucket(CreateBucketRequest.builder().bucket(bucket).build()).join();
			try (var backend = BackendFixture.startWithTargetStorageIntegration()) {
				var storage = backend.post("/api/v1/target-storages", registration(objectStorage.endpoint(), bucket));
				String storageId = JSON.readTree(storage.body()).path("id").asText();
				backend.put("/api/v1/target-storages/" + storageId + "/activation",
						"{\"expectedRegistrationRevision\":2,\"activated\":true}");
				JsonNode publication = JSON.readTree(backend.post("/api/v1/dataset-publications", """
						{
						  "targetStorageId":"%s",
						  "formatIdentity":"mosaicml-streaming-mds@2",
						  "manifestIdentity":"sha256:%s",
						  "contentFingerprint":"sha256:%s",
						  "objectCount":1,
						  "byteCount":1
						}
						""".formatted(storageId, "4".repeat(64), "5".repeat(64))).body());
				String publicationId = publication.path("publicationId").asText();
				String lateObject = publication.path("payloadLocation").asText() + "/late-object";

				var started = backend.post("/api/v1/dataset-publications/" + publicationId + "/transfer-start", "{}");
				assertThat(started.statusCode()).as(started.body()).isEqualTo(200);
				assertThat(JSON
					.readTree(
							backend.post("/api/v1/dataset-publications/" + publicationId + "/completion", "{}").body())
					.path("state")
					.asText()).isEqualTo("uploading");
				backend.post("/api/v1/dataset-publications/" + publicationId + "/abort", "{}");
				backend.restart();
				administrator
					.putObject(PutObjectRequest.builder().bucket(bucket).key(lateObject).build(),
							AsyncRequestBody.fromString("x"))
					.join();

				assertThat(JSON.readTree(backend.get("/api/v1/dataset-publications/" + publicationId).body())
					.path("state")
					.asText()).isEqualTo("aborting");
				var stopped = backend.post("/api/v1/dataset-publications/" + publicationId + "/transfer-stop", "{}");
				assertThat(stopped.statusCode()).as(stopped.body()).isEqualTo(200);
				assertThat(awaitPublicationState(backend, publicationId, "aborted").path("state").asText())
					.isEqualTo("aborted");
				assertThat(remoteKeys(administrator, bucket, publication.path("payloadLocation").asText())).isEmpty();
			}
		}
	}

	@Test
	void abortDeletesOnlyAllocatedPrefixesIncludingIncompleteMultipartUploads() throws Exception {
		try (var objectStorage = SeaweedFsFixture.start(); var administrator = administrator(objectStorage)) {
			objectStorage.awaitReady(administrator);
			String bucket = "publication-cleanup-" + UUID.randomUUID();
			administrator.createBucket(CreateBucketRequest.builder().bucket(bucket).build()).join();
			try (var backend = BackendFixture.startWithTargetStorageIntegration()) {
				var storage = backend.post("/api/v1/target-storages", registration(objectStorage.endpoint(), bucket));
				String storageId = JSON.readTree(storage.body()).path("id").asText();
				backend.put("/api/v1/target-storages/" + storageId + "/activation",
						"{\"expectedRegistrationRevision\":2,\"activated\":true}");
				JsonNode publication = JSON.readTree(backend.post("/api/v1/dataset-publications", """
						{
						  "targetStorageId":"%s",
						  "formatIdentity":"mosaicml-streaming-mds@2",
						  "manifestIdentity":"sha256:%s",
						  "contentFingerprint":"sha256:%s",
						  "objectCount":1,
						  "byteCount":1
						}
						""".formatted(storageId, "0".repeat(64), "1".repeat(64))).body());
				String publicationId = publication.path("publicationId").asText();
				String payload = publication.path("payloadLocation").asText();
				String operation = publication.path("operationLocation").asText();
				String payloadSibling = payload + "-sibling/keep";
				String operationSibling = operation + "-sibling/keep";
				for (String key : java.util.List.of(payload + "/object", operation + "/manifest.json", payloadSibling,
						operationSibling)) {
					administrator
						.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(),
								AsyncRequestBody.fromString("x"))
						.join();
				}
				for (String key : java.util.List.of(payload + "/unfinished", operation + "/unfinished",
						payload + "-sibling/unfinished")) {
					administrator
						.createMultipartUpload(CreateMultipartUploadRequest.builder().bucket(bucket).key(key).build())
						.join();
				}
				backend.put("/api/v1/dataset-publications/" + publicationId + "/progress",
						"{\"uploadedObjectCount\":1,\"uploadedByteCount\":1}");

				objectStorage.pause();
				boolean storagePaused = true;
				CommandResult abort;
				try {
					var accepted = backend.post("/api/v1/dataset-publications/" + publicationId + "/abort", "{}");
					assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
					JsonNode failedCleanup = awaitPublicationState(backend, publicationId, "failed-cleanup");
					assertThat(failedCleanup.path("retryable").asBoolean()).isTrue();
					assertThat(failedCleanup.path("failureCode").asText()).isEqualTo("DATASET_CLEANUP_UNAVAILABLE");
					assertThat(failedCleanup.path("failureDetail").asText()).doesNotContain("Exception", "secret",
							objectStorage.endpoint().toString());

					backend.restart();
					assertThat(JSON.readTree(backend.get("/api/v1/dataset-publications/" + publicationId).body())
						.path("state")
						.asText()).isEqualTo("failed-cleanup");
					objectStorage.unpause();
					storagePaused = false;
					objectStorage.awaitReady(administrator);
					abort = runAbortCommand(backend.baseUri(), publicationId);
				}
				finally {
					if (storagePaused) {
						objectStorage.unpause();
						objectStorage.awaitReady(administrator);
					}
				}
				assertThat(abort.exitCode()).as(abort.stderr()).isZero();
				JsonNode aborted = JSON.readTree(abort.stdout());
				assertThat(aborted.path("state").asText()).isEqualTo("aborted");
				assertThat(aborted.path("retryGuidance").asText()).contains("every allocated object");
				assertThat(remoteKeys(administrator, bucket, payload + "/")).isEmpty();
				assertThat(remoteKeys(administrator, bucket, operation + "/")).isEmpty();
				assertThat(multipartKeys(administrator, bucket, payload + "/")).isEmpty();
				assertThat(multipartKeys(administrator, bucket, operation + "/")).isEmpty();
				assertThat(remoteKeys(administrator, bucket, payload + "-sibling/")).containsExactly(payloadSibling);
				assertThat(remoteKeys(administrator, bucket, operation + "-sibling/"))
					.containsExactly(operationSibling);
				assertThat(multipartKeys(administrator, bucket, payload + "-sibling/"))
					.containsExactly(payload + "-sibling/unfinished");

				var repeated = backend.post("/api/v1/dataset-publications/" + publicationId + "/abort", "{}");
				assertThat(repeated.statusCode()).as(repeated.body()).isEqualTo(202);
				assertThat(JSON.readTree(repeated.body()).path("state").asText()).isEqualTo("aborted");
			}
		}
	}

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
				var changedPreference = backend.post("/api/v1/dataset-publications/" + publicationId + "/resume",
						request.replace("\"targetStorageId\"",
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

				backend.put("/api/v1/dataset-publications/" + publicationId + "/failure",
						"{\"failureCode\":\"DATASET_UPLOAD_CONFLICT\"}");
				var lateProgress = backend.put("/api/v1/dataset-publications/" + publicationId + "/progress",
						"{\"uploadedObjectCount\":3,\"uploadedByteCount\":21}");
				JsonNode failedClosed = JSON.readTree(lateProgress.body());
				assertThat(failedClosed.path("state").asText()).isEqualTo("failed");
				assertThat(failedClosed.path("retryable").asBoolean()).isFalse();
				assertThat(failedClosed.path("uploadedObjectCount").asLong()).isEqualTo(2);
				assertThat(failedClosed.path("uploadedByteCount").asLong()).isEqualTo(13);
				assertThat(failedClosed.path("retryGuidance").asText()).doesNotContain("--resume")
					.contains("cannot be resumed");
				var lateFailure = backend.put("/api/v1/dataset-publications/" + publicationId + "/failure",
						"{\"failureCode\":\"DATASET_UPLOAD_FAILED\"}");
				JsonNode stillFailedClosed = JSON.readTree(lateFailure.body());
				assertThat(stillFailedClosed.path("failureCode").asText()).isEqualTo("DATASET_UPLOAD_CONFLICT");
				assertThat(stillFailedClosed.path("retryable").asBoolean()).isFalse();
				assertThat(stillFailedClosed.path("retryGuidance").asText()).doesNotContain("--resume");
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

				DatasetPublicationCommitGateTestConfiguration.failNextCleanup();
				CommandResult command = runCommand(corpus, backend.baseUri(), storageId);
				assertThat(command.exitCode()).as(command.stderr()).isZero();

				JsonNode failedCleanup = JSON.readTree(command.stdout());
				assertThat(failedCleanup.path("state").asText()).isEqualTo("failed-cleanup");
				assertThat(failedCleanup.path("failureDetail").asText()).doesNotContain("Exception", "secret", "/tmp");
				assertThat(backend.get("/api/v1/datasets/" + failedCleanup.path("datasetId").asText()).statusCode())
					.isEqualTo(200);
				assertThat(backend.get("/api/v1/dataset-catalog/" + failedCleanup.path("definitionId").asText())
					.statusCode()).isEqualTo(200);
				assertThat(remoteKeys(administrator, bucket, failedCleanup.path("payloadLocation").asText() + "/"))
					.hasSize(expected.path("objectCount").asInt());
				var cleanupRetry = backend.post(
						"/api/v1/dataset-publications/" + failedCleanup.path("publicationId").asText() + "/cleanup",
						"{}");
				assertThat(cleanupRetry.statusCode()).as(cleanupRetry.body()).isEqualTo(202);
				JsonNode result = awaitPublicationState(backend, failedCleanup.path("publicationId").asText(),
						"committed");
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
					.isEqualTo(2);
				assertThat(remoteKeys(administrator, bucket, result.path("operationLocation").asText() + "/"))
					.isEmpty();
				assertThat(remoteKeys(administrator, bucket, result.path("payloadLocation").asText() + "/"))
					.hasSize(expected.path("objectCount").asInt());
				var abortCommitted = backend
					.post("/api/v1/dataset-publications/" + result.path("publicationId").asText() + "/abort", "{}");
				assertThat(abortCommitted.statusCode()).as(abortCommitted.body()).isEqualTo(409);

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
				String collidingRequest = """
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
						result.path("manifestIdentity").asText(), collidingFingerprint);
				var collidingInitiation = backend.post("/api/v1/dataset-publications", collidingRequest);
				assertThat(collidingInitiation.statusCode()).as(collidingInitiation.body()).isEqualTo(201);
				JsonNode colliding = JSON.readTree(collidingInitiation.body());
				assertThat(colliding.path("versionLabel").asText()).isEqualTo(collidingFingerprint.substring(7, 25));
				assertThat(colliding.path("contentFingerprint").asText()).isEqualTo(collidingFingerprint);
				var resumedExisting = backend.post(
						"/api/v1/dataset-publications/" + colliding.path("publicationId").asText() + "/resume",
						collidingRequest);
				assertThat(resumedExisting.statusCode()).as(resumedExisting.body()).isEqualTo(200);
				assertThat(JSON.readTree(resumedExisting.body()).path("datasetId")).isEqualTo(result.path("datasetId"));
				var changedExistingDecision = backend.post(
						"/api/v1/dataset-publications/" + colliding.path("publicationId").asText() + "/resume",
						collidingRequest.replace("\"preferredDefinitionDecision\":\"advance\"",
								"\"preferredDefinitionDecision\":\"keep\""));
				assertThat(changedExistingDecision.statusCode()).as(changedExistingDecision.body()).isEqualTo(409);
				assertThat(changedExistingDecision.body()).contains("SKYWRIGHT_DATASET_PUBLICATION_CONFLICT");
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

				DatasetPublicationCommitGateTestConfiguration.blockNextCommit();
				var boundaryArguments = new ArrayList<>(java.util.List.of("uv", "run", "--project", "sdk", "--locked",
						"skywright-datasets", "publish", corpus.toString(), "--control-plane",
						backend.baseUri().toString(), "--target-storage", storageId, "--version-label",
						"commit-boundary", "--dataset", result.path("datasetId").asText(),
						"--expected-dataset-revision", "5", "--keep-preferred"));
				Process boundaryCommand = startCommand(boundaryArguments);
				var boundaryErrors = new BufferedReader(
						new InputStreamReader(boundaryCommand.getErrorStream(), StandardCharsets.UTF_8));
				JsonNode boundaryIdentity = JSON.readTree(boundaryErrors.readLine());
				DatasetPublicationCommitGateTestConfiguration.awaitNextCommitStarted();
				try (var boundaryRequests = Executors.newVirtualThreadPerTaskExecutor()) {
					var cancellation = boundaryRequests.submit(() -> backend.post("/api/v1/dataset-publications/"
							+ boundaryIdentity.path("publicationId").asText() + "/abort", "{}"));
					DatasetPublicationCommitGateTestConfiguration.releaseNextCommit();
					assertThat(cancellation.get().statusCode()).isEqualTo(409);
				}
				String boundaryOutput = new String(boundaryCommand.getInputStream().readAllBytes(),
						StandardCharsets.UTF_8);
				String boundaryErrorTail = boundaryErrors.lines().collect(Collectors.joining("\n"));
				assertThat(boundaryCommand.waitFor()).as(boundaryErrorTail).isZero();
				assertThat(JSON.readTree(boundaryOutput).path("state").asText()).isEqualTo("committed");

				Set<String> keys = administrator.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build())
					.join()
					.contents()
					.stream()
					.map(value -> value.key())
					.collect(Collectors.toSet());
				String payload = result.path("payloadLocation").asText();
				Set<String> expectedKeys = new HashSet<>();
				localBefore.keySet().forEach(key -> expectedKeys.add(payload + "/" + key));
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
					assertThat(persistedLineage.body()).contains("\"revision\":6");
					var persistedVersions = restarted
						.get("/api/v1/dataset-catalog?datasetId=" + result.path("datasetId").asText() + "&limit=100");
					assertThat(JSON.readTree(persistedVersions.body()).path("items")).hasSize(6);
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

	@Test
	void installedCommandResumesPartiallyAndFullyTransferredPublications() throws Exception {
		try (var objectStorage = SeaweedFsFixture.start(); var administrator = administrator(objectStorage)) {
			objectStorage.awaitReady(administrator);
			String bucket = "publication-resume-" + UUID.randomUUID();
			administrator.createBucket(CreateBucketRequest.builder().bucket(bucket).build()).join();
			JsonNode fixture = JSON.readTree(Path.of(System.getProperty("repository.root"), "tests", "fixtures",
					"dataset-publication", "mds-v2-contract.json")
				.toFile());
			Path corpus = Files.createDirectory(this.temporaryDirectory.resolve("resumed-corpus"));
			Map<String, byte[]> files = new HashMap<>();
			for (JsonNode file : fixture.path("files")) {
				String objectKey = file.path("objectKey").asText();
				byte[] bytes = Base64.getDecoder().decode(file.path("base64").asText());
				Path destination = corpus.resolve(objectKey);
				Files.createDirectories(destination.getParent());
				Files.write(destination, bytes);
				files.put(objectKey, bytes);
			}
			JsonNode expected = fixture.path("expected");

			try (var backend = BackendFixture.startWithTargetStorageIntegration()) {
				var storage = backend.post("/api/v1/target-storages", registration(objectStorage.endpoint(), bucket));
				String storageId = JSON.readTree(storage.body()).path("id").asText();
				backend.put("/api/v1/target-storages/" + storageId + "/activation",
						"{\"expectedRegistrationRevision\":2,\"activated\":true}");

				objectStorage.pause();
				boolean storagePaused = true;
				Process interrupted = startCommand(corpus, backend.baseUri(), storageId, null);
				try {
					var interruptedErrors = new BufferedReader(
							new InputStreamReader(interrupted.getErrorStream(), StandardCharsets.UTF_8));
					JsonNode identity = JSON.readTree(interruptedErrors.readLine());
					String interruptedPublicationId = identity.path("publicationId").asText();
					assertThat(identity.path("event").asText()).isEqualTo("dataset-publication-identity");
					if (!interrupted.waitFor(10, TimeUnit.SECONDS)) {
						interrupted.destroyForcibly();
						interrupted.waitFor();
					}
					assertThat(interrupted.exitValue()).isNotZero();
					objectStorage.unpause();
					storagePaused = false;
					objectStorage.awaitReady(administrator);
					CommandResult recovered = runCommand(corpus, backend.baseUri(), storageId,
							interruptedPublicationId);
					assertThat(recovered.exitCode()).as(recovered.stderr()).isZero();
					assertThat(JSON.readTree(recovered.stdout()).path("publicationId").asText())
						.isEqualTo(interruptedPublicationId);
				}
				finally {
					if (storagePaused) {
						objectStorage.unpause();
						objectStorage.awaitReady(administrator);
					}
				}

				try (var proxy = new DroppingCompletionProxy(backend.baseUri())) {
					CommandResult reconciled = runCommand(corpus, proxy.baseUri(), storageId);
					assertThat(reconciled.exitCode()).as(reconciled.stderr()).isZero();
					assertThat(JSON.readTree(reconciled.stdout()).path("state").asText()).isEqualTo("committed");
					assertThat(proxy.droppedCompletion()).isTrue();
				}

				try (var proxy = new RestartOnCommittedReadProxy(backend)) {
					CommandResult interruptedResult = runCommand(corpus, proxy.baseUri(), storageId);
					assertThat(interruptedResult.exitCode()).as(interruptedResult.stderr()).isNotZero();
					String publicationId = publicationIdentity(interruptedResult.stderr());
					assertThat(proxy.restarted()).isTrue();
					CommandResult recoveredResult = runCommand(corpus, backend.baseUri(), storageId, publicationId);
					assertThat(recoveredResult.exitCode()).as(recoveredResult.stderr()).isZero();
					assertThat(JSON.readTree(recoveredResult.stdout()).path("publicationId").asText())
						.isEqualTo(publicationId);
				}

				for (int transferredObjectCount : java.util.List.of(1, files.size())) {
					String initiation = """
							{
							  "targetStorageId":"%s",
							  "formatIdentity":"%s",
							  "manifestIdentity":"%s",
							  "contentFingerprint":"%s",
							  "objectCount":%d,
							  "byteCount":%d
							}
							""".formatted(storageId, expected.path("formatIdentity").asText(),
							expected.path("manifestIdentity").asText(), expected.path("contentFingerprint").asText(),
							expected.path("objectCount").asLong(), expected.path("byteCount").asLong());
					JsonNode publication = JSON
						.readTree(backend.post("/api/v1/dataset-publications", initiation).body());
					String publicationId = publication.path("publicationId").asText();
					String payloadLocation = publication.path("payloadLocation").asText();
					long transferredBytes = 0;
					int transferred = 0;
					for (var entry : files.entrySet()) {
						if (transferred == transferredObjectCount) {
							break;
						}
						byte[] digest = MessageDigest.getInstance("SHA-256").digest(entry.getValue());
						administrator
							.putObject(PutObjectRequest.builder()
								.bucket(bucket)
								.key(payloadLocation + "/" + entry.getKey())
								.checksumSHA256(Base64.getEncoder().encodeToString(digest))
								.metadata(Map.of("skywright-sha256", HexFormat.of().formatHex(digest)))
								.build(), AsyncRequestBody.fromBytes(entry.getValue()))
							.join();
						transferredBytes += entry.getValue().length;
						transferred++;
					}
					backend.put("/api/v1/dataset-publications/" + publicationId + "/progress",
							"{\"uploadedObjectCount\":" + transferred + ",\"uploadedByteCount\":" + transferredBytes
									+ "}");
					if (transferredObjectCount == 1) {
						backend.restart();
					}
					else {
						administrator
							.putObject(
									PutObjectRequest.builder()
										.bucket(bucket)
										.key(publication.path("operationLocation").asText() + "/manifest.json")
										.build(),
									AsyncRequestBody.fromBytes(
											Base64.getDecoder().decode(expected.path("manifestBase64").asText())))
							.join();
						objectStorage.pause();
						var storagePausedDuringRestart = new AtomicBoolean(true);
						try {
							var completion = backend
								.post("/api/v1/dataset-publications/" + publicationId + "/completion", "{}");
							assertThat(completion.statusCode()).as(completion.body()).isEqualTo(202);
							assertThat(JSON.readTree(completion.body()).path("state").asText()).isEqualTo("verifying");
							backend.restart(() -> {
								objectStorage.unpause();
								storagePausedDuringRestart.set(false);
								objectStorage.awaitReady(administrator);
							});
						}
						finally {
							if (storagePausedDuringRestart.get()) {
								objectStorage.unpause();
							}
							objectStorage.awaitReady(administrator);
						}
					}

					CommandResult command = runCommand(corpus, backend.baseUri(), storageId, publicationId);
					assertThat(command.exitCode()).as(command.stderr()).isZero();
					JsonNode result = JSON.readTree(command.stdout());
					assertThat(result.path("state").asText()).isEqualTo("committed");
					assertThat(result.path("publicationId").asText()).isEqualTo(publicationId);
					assertThat(result.path("datasetId")).isEqualTo(publication.path("datasetId"));
					assertThat(result.path("definitionId")).isEqualTo(publication.path("definitionId"));
					assertThat(result.path("payloadLocation")).isEqualTo(publication.path("payloadLocation"));
				}

				JsonNode corrupt = JSON
					.readTree(backend
						.post("/api/v1/dataset-publications",
								"""
										{
										  "targetStorageId":"%s",
										  "formatIdentity":"%s",
										  "manifestIdentity":"%s",
										  "contentFingerprint":"%s",
										  "objectCount":%d,
										  "byteCount":%d
										}
										""".formatted(storageId, expected.path("formatIdentity").asText(),
										expected.path("manifestIdentity").asText(),
										expected.path("contentFingerprint").asText(),
										expected.path("objectCount").asLong(), expected.path("byteCount").asLong()))
						.body());
				String corruptPayload = corrupt.path("payloadLocation").asText();
				for (var entry : files.entrySet()) {
					byte[] content = entry.getKey().equals("index.json") ? "corrupt".getBytes(StandardCharsets.UTF_8)
							: entry.getValue();
					administrator
						.putObject(PutObjectRequest.builder()
							.bucket(bucket)
							.key(corruptPayload + "/" + entry.getKey())
							.build(), AsyncRequestBody.fromBytes(content))
						.join();
				}
				byte[] manifest = Base64.getDecoder().decode(expected.path("manifestBase64").asText());
				administrator
					.putObject(PutObjectRequest.builder()
						.bucket(bucket)
						.key(corrupt.path("operationLocation").asText() + "/manifest.json")
						.build(), AsyncRequestBody.fromBytes(manifest))
					.join();
				backend.put("/api/v1/dataset-publications/" + corrupt.path("publicationId").asText() + "/progress",
						"{\"uploadedObjectCount\":" + files.size() + ",\"uploadedByteCount\":"
								+ expected.path("byteCount").asLong() + "}");
				backend.post("/api/v1/dataset-publications/" + corrupt.path("publicationId").asText() + "/completion",
						"{}");
				JsonNode rejected = awaitTerminalPublication(backend, corrupt.path("publicationId").asText());
				assertThat(rejected.path("failureCode").asText()).isEqualTo("DATASET_REMOTE_MANIFEST_MISMATCH");
				assertThat(rejected.path("retryable").asBoolean()).isFalse();
				assertThat(backend.get("/api/v1/datasets/" + corrupt.path("datasetId").asText()).statusCode())
					.isEqualTo(404);
				assertThat(backend.get("/api/v1/dataset-catalog/" + corrupt.path("definitionId").asText()).statusCode())
					.isEqualTo(404);
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

	private static JsonNode awaitPublicationState(BackendFixture backend, String publicationId, String expectedState)
			throws Exception {
		for (int attempt = 0; attempt < 200; attempt++) {
			JsonNode publication = JSON.readTree(backend.get("/api/v1/dataset-publications/" + publicationId).body());
			if (publication.path("state").asText().equals(expectedState)) {
				return publication;
			}
			Thread.sleep(50);
		}
		throw new AssertionError("Dataset Publication did not reach state " + expectedState);
	}

	private static Set<String> remoteKeys(S3AsyncClient client, String bucket, String prefix) {
		return client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build())
			.join()
			.contents()
			.stream()
			.map(object -> object.key())
			.collect(Collectors.toSet());
	}

	private static Set<String> multipartKeys(S3AsyncClient client, String bucket, String prefix) {
		return client.listMultipartUploads(ListMultipartUploadsRequest.builder().bucket(bucket).prefix(prefix).build())
			.join()
			.uploads()
			.stream()
			.map(upload -> upload.key())
			.collect(Collectors.toSet());
	}

	private static CommandResult runCommand(Path corpus, URI controlPlane, String storageId, String... extra)
			throws Exception {
		var arguments = new java.util.ArrayList<>(
				java.util.List.of("uv", "run", "--project", "sdk", "--locked", "skywright-datasets", "publish",
						corpus.toString(), "--control-plane", controlPlane.toString(), "--target-storage", storageId));
		arguments.addAll(java.util.List.of(extra));
		Process command = startCommand(arguments);
		String stdout = new String(command.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		String stderr = new String(command.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
		return new CommandResult(command.waitFor(), stdout, stderr);
	}

	private static CommandResult runAbortCommand(URI controlPlane, String publicationId) throws Exception {
		Process command = startCommand(java.util.List.of("uv", "run", "--project", "sdk", "--locked",
				"skywright-datasets", "abort", publicationId, "--control-plane", controlPlane.toString()));
		String stdout = new String(command.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		String stderr = new String(command.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
		return new CommandResult(command.waitFor(), stdout, stderr);
	}

	private static CommandResult runCommand(Path corpus, URI controlPlane, String storageId, String publicationId)
			throws Exception {
		return runCommand(corpus, controlPlane, storageId, "--resume", publicationId);
	}

	private static Process startCommand(Path corpus, URI controlPlane, String storageId, String publicationId)
			throws Exception {
		var arguments = new ArrayList<>(
				java.util.List.of("uv", "run", "--project", "sdk", "--locked", "skywright-datasets", "publish",
						corpus.toString(), "--control-plane", controlPlane.toString(), "--target-storage", storageId));
		if (publicationId != null) {
			arguments.add("--resume");
			arguments.add(publicationId);
		}
		return startCommand(arguments);
	}

	private static Process startCommand(java.util.List<String> arguments) throws Exception {
		var commandBuilder = new ProcessBuilder(arguments)
			.directory(Path.of(System.getProperty("repository.root")).toFile())
			.redirectErrorStream(false);
		commandBuilder.environment()
			.putAll(Map.of("AWS_ACCESS_KEY_ID", "test-key", "AWS_SECRET_ACCESS_KEY", "test-secret", "AWS_REGION",
					"us-east-1", "AWS_MAX_ATTEMPTS", "1"));
		return commandBuilder.start();
	}

	private static String publicationIdentity(String stderr) throws Exception {
		return JSON.readTree(stderr.lines().findFirst().orElseThrow()).path("publicationId").asText();
	}

	private record CommandResult(int exitCode, String stdout, String stderr) {
	}

	private static final class DroppingCompletionProxy implements AutoCloseable {

		private final HttpServer server;

		private final URI upstream;

		private final HttpClient client = HttpClient.newHttpClient();

		private final AtomicBoolean droppedCompletion = new AtomicBoolean();

		private DroppingCompletionProxy(URI upstream) throws Exception {
			this.upstream = upstream;
			this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			this.server.createContext("/", exchange -> {
				try {
					byte[] requestBody = exchange.getRequestBody().readAllBytes();
					var request = HttpRequest.newBuilder(this.upstream.resolve(exchange.getRequestURI().toString()));
					String requestContentType = exchange.getRequestHeaders().getFirst("Content-Type");
					if (requestContentType != null) {
						request.header("Content-Type", requestContentType);
					}
					request.method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(requestBody));
					HttpResponse<byte[]> response = this.client.send(request.build(),
							HttpResponse.BodyHandlers.ofByteArray());
					if (exchange.getRequestURI().getPath().endsWith("/completion")
							&& this.droppedCompletion.compareAndSet(false, true)) {
						exchange.close();
						return;
					}
					String contentType = response.headers().firstValue("Content-Type").orElse("application/json");
					exchange.getResponseHeaders().set("Content-Type", contentType);
					exchange.sendResponseHeaders(response.statusCode(), response.body().length);
					exchange.getResponseBody().write(response.body());
				}
				catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					exchange.close();
				}
				finally {
					exchange.close();
				}
			});
			this.server.start();
		}

		private URI baseUri() {
			return URI.create("http://127.0.0.1:" + this.server.getAddress().getPort());
		}

		private boolean droppedCompletion() {
			return this.droppedCompletion.get();
		}

		@Override
		public void close() {
			this.server.stop(0);
		}

	}

	private static final class RestartOnCommittedReadProxy implements AutoCloseable {

		private final HttpServer server;

		private final BackendFixture backend;

		private final HttpClient client = HttpClient.newHttpClient();

		private final AtomicBoolean restarted = new AtomicBoolean();

		private RestartOnCommittedReadProxy(BackendFixture backend) throws Exception {
			this.backend = backend;
			this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			this.server.createContext("/", exchange -> {
				try {
					byte[] requestBody = exchange.getRequestBody().readAllBytes();
					var request = HttpRequest
						.newBuilder(this.backend.baseUri().resolve(exchange.getRequestURI().toString()));
					String requestContentType = exchange.getRequestHeaders().getFirst("Content-Type");
					if (requestContentType != null) {
						request.header("Content-Type", requestContentType);
					}
					request.method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(requestBody));
					HttpResponse<byte[]> response = this.client.send(request.build(),
							HttpResponse.BodyHandlers.ofByteArray());
					if (exchange.getRequestMethod().equals("GET")
							&& exchange.getRequestURI().getPath().contains("/dataset-publications/")
							&& JSON.readTree(response.body()).path("state").asText().equals("committed")
							&& this.restarted.compareAndSet(false, true)) {
						this.backend.restart();
						exchange.close();
						return;
					}
					String contentType = response.headers().firstValue("Content-Type").orElse("application/json");
					exchange.getResponseHeaders().set("Content-Type", contentType);
					exchange.sendResponseHeaders(response.statusCode(), response.body().length);
					exchange.getResponseBody().write(response.body());
				}
				catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					exchange.close();
				}
				finally {
					exchange.close();
				}
			});
			this.server.start();
		}

		private URI baseUri() {
			return URI.create("http://127.0.0.1:" + this.server.getAddress().getPort());
		}

		private boolean restarted() {
			return this.restarted.get();
		}

		@Override
		public void close() {
			this.server.stop(0);
		}

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
