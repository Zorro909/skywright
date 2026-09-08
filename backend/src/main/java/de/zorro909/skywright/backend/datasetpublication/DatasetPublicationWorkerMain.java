package de.zorro909.skywright.backend.datasetpublication;

import de.zorro909.skywright.backend.datasetcatalog.DatasetManifestEntry;
import java.io.IOException;
import java.io.InterruptedIOException;
import de.zorro909.skywright.backend.worker.TransferObjects;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Standalone managed Transfer Worker entry point. */
public final class DatasetPublicationWorkerMain {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	static final int TEMPORARY_STORAGE_FAILURE_EXIT = 74;
	static final int MAX_VERIFICATION_CONCURRENCY = 16;

	private static final int MAX_MANIFEST_BYTES = 16 * 1024 * 1024;

	private static final int MAX_MANIFEST_OBJECTS = 100_000;

	private DatasetPublicationWorkerMain() {
	}

	public static void main(String[] arguments) throws IOException {
		if (arguments.length != 2) {
			throw new IllegalArgumentException("Expected job and result paths");
		}
		Path resultPath = Path.of(arguments[1]);
		DatasetPublicationWorkerResult result;
		try {
			var credential = JSON.readValue(System.in, DatasetPublicationWorkerCredential.class);
			var job = JSON.readValue(Path.of(arguments[0]).toFile(), DatasetPublicationWorkerJob.class);
			result = execute(job, credential);
		}
		catch (WorkerFailure failure) {
			result = new DatasetPublicationWorkerResult(false, List.of(), 0, 0, null, ProcessHandle.current().pid(),
					failure.code, failure.retryable);
		}
		catch (RuntimeException failure) {
			result = new DatasetPublicationWorkerResult(false, List.of(), 0, 0, null, ProcessHandle.current().pid(),
					"DATASET_VERIFICATION_UNAVAILABLE", true);
		}
		Path pending = resultPath.resolveSibling(resultPath.getFileName() + ".pending");
		try {
			JSON.writeValue(pending.toFile(), result);
			Files.move(pending, resultPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException | JacksonException failure) {
			System.err.println("DATASET_WORKER_TEMPORARY_STORAGE_UNAVAILABLE");
			System.exit(TEMPORARY_STORAGE_FAILURE_EXIT);
		}
	}

	private static DatasetPublicationWorkerResult execute(DatasetPublicationWorkerJob job,
			DatasetPublicationWorkerCredential credential) {
		return switch (job.action()) {
			case VERIFY -> verify(job, credential);
			case ABORT -> cleanup(job, credential, false);
			case CLEAN_OPERATION -> cleanup(job, credential, true);
		};
	}

	private static DatasetPublicationWorkerResult cleanup(DatasetPublicationWorkerJob job,
			DatasetPublicationWorkerCredential credential, boolean operationOnly) {
		try (S3AsyncClient client = client(job, credential)) {
			List<String> prefixes = operationOnly ? List.of(job.operationLocation())
					: List.of(job.payloadLocation(), job.operationLocation());
			int consecutiveEmptyInventories = 0;
			for (int pass = 0; pass < 20 && consecutiveEmptyInventories < 2; pass++) {
				for (String prefix : prefixes) {
					String allocatedPrefix = prefix.endsWith("/") ? prefix : prefix + "/";
					abortMultipartUploads(client, job.bucket(), allocatedPrefix);
					deleteObjects(client, job.bucket(), allocatedPrefix);
				}
				boolean empty = true;
				for (String prefix : prefixes) {
					String allocatedPrefix = prefix.endsWith("/") ? prefix : prefix + "/";
					empty &= remoteKeys(client, job.bucket(), allocatedPrefix).isEmpty()
							&& !hasMultipartUploads(client, job.bucket(), allocatedPrefix);
				}
				consecutiveEmptyInventories = empty ? consecutiveEmptyInventories + 1 : 0;
				if (consecutiveEmptyInventories < 2) {
					TimeUnit.MILLISECONDS.sleep(100);
				}
			}
			if (consecutiveEmptyInventories < 2) {
				throw new WorkerFailure("DATASET_CLEANUP_UNAVAILABLE", true);
			}
			return new DatasetPublicationWorkerResult(true, List.of(), 0, 0, Instant.now(),
					ProcessHandle.current().pid(), null, false);
		}
		catch (InterruptedException failure) {
			Thread.currentThread().interrupt();
			throw new WorkerFailure("DATASET_CLEANUP_UNAVAILABLE", true);
		}
		catch (WorkerFailure failure) {
			throw failure;
		}
		catch (RuntimeException failure) {
			throw new WorkerFailure("DATASET_CLEANUP_UNAVAILABLE", true);
		}
	}

	private static void deleteObjects(S3AsyncClient client, String bucket, String prefix) {
		List<String> keys = new ArrayList<>(remoteKeys(client, bucket, prefix));
		for (int offset = 0; offset < keys.size(); offset += 1000) {
			List<ObjectIdentifier> objects = keys.subList(offset, Math.min(offset + 1000, keys.size()))
				.stream()
				.map(key -> ObjectIdentifier.builder().key(key).build())
				.toList();
			var response = client
				.deleteObjects(DeleteObjectsRequest.builder()
					.bucket(bucket)
					.delete(Delete.builder().objects(objects).quiet(true).build())
					.build())
				.join();
			if (!response.errors().isEmpty()) {
				throw new WorkerFailure("DATASET_CLEANUP_UNAVAILABLE", true);
			}
		}
	}

	private static void abortMultipartUploads(S3AsyncClient client, String bucket, String prefix) {
		TransferObjects.abortUploads(client, bucket, prefix, key -> key.startsWith(prefix), 100_000);
	}

	private static boolean hasMultipartUploads(S3AsyncClient client, String bucket, String prefix) {
		var page = client
			.listMultipartUploads(
					ListMultipartUploadsRequest.builder().bucket(bucket).prefix(prefix).maxUploads(1).build())
			.join();
		return !page.uploads().isEmpty();
	}

	private static DatasetPublicationWorkerResult verify(DatasetPublicationWorkerJob job,
			DatasetPublicationWorkerCredential credential) {
		try (S3AsyncClient client = client(job, credential)) {
			if (job.verificationConcurrency() < 1 || job.verificationConcurrency() > MAX_VERIFICATION_CONCURRENCY) {
				throw mismatch();
			}
			byte[] manifestBytes = readManifest(client, job);
			if (!digest(manifestBytes).equals(job.manifestIdentity())) {
				throw mismatch();
			}
			String fingerprintDocument = "{\"format\":\"" + job.formatIdentity() + "\",\"manifest\":\""
					+ job.manifestIdentity() + "\",\"version\":\"skywright-dataset-content@1\"}";
			if (!digest(fingerprintDocument.getBytes(StandardCharsets.UTF_8)).equals(job.contentFingerprint())) {
				throw mismatch();
			}
			List<ManifestObject> objects = parseManifest(manifestBytes, job);
			Set<String> expectedKeys = new HashSet<>();
			List<DatasetManifestEntry> entries = new ArrayList<>();
			try (var executor = Executors.newFixedThreadPool(job.verificationConcurrency())) {
				List<Future<DatasetManifestEntry>> verifications = new ArrayList<>();
				for (ManifestObject object : objects) {
					String remoteKey = key(job.payloadLocation(), object.objectKey());
					expectedKeys.add(remoteKey);
					verifications.add(executor.submit(() -> verifyObject(client, job.bucket(), remoteKey, object)));
				}
				for (Future<DatasetManifestEntry> verification : verifications) {
					entries.add(verification.get());
				}
			}
			if (!remoteKeys(client, job.bucket(), job.payloadLocation() + "/").equals(expectedKeys)) {
				throw mismatch();
			}
			return new DatasetPublicationWorkerResult(true, entries, objects.size(),
					objects.stream().mapToLong(ManifestObject::byteCount).sum(), Instant.now(),
					ProcessHandle.current().pid(), null, false);
		}
		catch (WorkerFailure failure) {
			throw failure;
		}
		catch (InterruptedException failure) {
			Thread.currentThread().interrupt();
			throw new WorkerFailure("DATASET_VERIFICATION_UNAVAILABLE", true);
		}
		catch (ExecutionException failure) {
			if (failure.getCause() instanceof WorkerFailure workerFailure) {
				throw workerFailure;
			}
			throw new WorkerFailure("DATASET_VERIFICATION_UNAVAILABLE", true);
		}
		catch (RuntimeException failure) {
			throw new WorkerFailure("DATASET_VERIFICATION_UNAVAILABLE", true);
		}
	}

	private static List<ManifestObject> parseManifest(byte[] bytes, DatasetPublicationWorkerJob job) {
		try {
			JsonNode root = JSON.readTree(bytes);
			if (!"skywright-dataset-manifest@1".equals(root.path("version").asText())
					|| !job.formatIdentity().equals(root.path("format").asText())
					|| job.objectCount() != root.path("objectCount").asLong(-1)
					|| job.byteCount() != root.path("byteCount").asLong(-1) || !root.path("objects").isArray()
					|| job.objectCount() < 0 || job.objectCount() > MAX_MANIFEST_OBJECTS) {
				throw mismatch();
			}
			List<ManifestObject> objects = new ArrayList<>();
			Set<String> keys = new HashSet<>();
			String previousKey = null;
			for (JsonNode item : root.path("objects")) {
				String objectKey = item.path("objectKey").asText();
				long byteCount = item.path("byteCount").asLong(-1);
				String sha256 = item.path("sha256").asText();
				if (!safeKey(objectKey) || byteCount < 0 || !sha256.matches("sha256:[0-9a-f]{64}")
						|| !keys.add(objectKey)
						|| previousKey != null
								&& java.util.Arrays.compareUnsigned(previousKey.getBytes(StandardCharsets.UTF_8),
										objectKey.getBytes(StandardCharsets.UTF_8)) >= 0) {
					throw mismatch();
				}
				objects.add(new ManifestObject(objectKey, byteCount, sha256));
				previousKey = objectKey;
			}
			if (objects.size() != job.objectCount()
					|| objects.stream().mapToLong(ManifestObject::byteCount).reduce(0, Math::addExact) != job
						.byteCount()) {
				throw mismatch();
			}
			return List.copyOf(objects);
		}
		catch (JacksonException | ArithmeticException failure) {
			throw mismatch();
		}
	}

	private static byte[] readManifest(S3AsyncClient client, DatasetPublicationWorkerJob job) {
		var response = client
			.getObject(GetObjectRequest.builder()
				.bucket(job.bucket())
				.key(key(job.operationLocation(), "manifest.json"))
				.build(), AsyncResponseTransformer.toBlockingInputStream())
			.join();
		try {
			if (response.response().contentLength() > MAX_MANIFEST_BYTES) {
				throw mismatch();
			}
			byte[] bytes = response.readNBytes(MAX_MANIFEST_BYTES + 1);
			if (bytes.length > MAX_MANIFEST_BYTES || bytes.length != response.response().contentLength()) {
				throw mismatch();
			}
			return bytes;
		}
		catch (IOException failure) {
			throw new WorkerFailure("DATASET_VERIFICATION_UNAVAILABLE", true);
		}
		finally {
			response.abort();
		}
	}

	private static DatasetManifestEntry verifyObject(S3AsyncClient client, String bucket, String key,
			ManifestObject object) {

		try {
			TransferObjects.verify(client, bucket, key, object.byteCount(), object.sha256().substring(7));
			return new DatasetManifestEntry(object.objectKey(), object.byteCount(),
					Base64.getEncoder().encodeToString(HexFormat.of().parseHex(object.sha256().substring(7))));
		}
		catch (TransferObjects.IntegrityMismatch failure) {
			throw mismatch();
		}
		catch (InterruptedIOException failure) {
			throw new WorkerFailure("DATASET_VERIFICATION_INTERRUPTED", true);
		}
		catch (IOException failure) {
			throw new WorkerFailure("DATASET_VERIFICATION_UNAVAILABLE", true);
		}
	}

	private static Set<String> remoteKeys(S3AsyncClient client, String bucket, String prefix) {
		Set<String> result = new HashSet<>();
		String token = null;
		do {
			var page = client
				.listObjectsV2(
						ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).continuationToken(token).build())
				.join();
			page.contents().forEach(object -> result.add(object.key()));
			token = page.isTruncated() ? page.nextContinuationToken() : null;
		}
		while (token != null);
		return result;
	}

	private static S3AsyncClient client(DatasetPublicationWorkerJob job,
			DatasetPublicationWorkerCredential credential) {
		AwsCredentials awsCredential = credential.sessionToken() == null
				? AwsBasicCredentials.create(credential.accessKeyId(), credential.secretAccessKey())
				: AwsSessionCredentials.create(credential.accessKeyId(), credential.secretAccessKey(),
						credential.sessionToken());
		return TransferObjects.client(job.endpoint(), job.region(), StaticCredentialsProvider.create(awsCredential),
				job.pathStyleAccess(), job.chunkedEncoding(),
				TransferObjects.checksumCalculation(job.checksumCalculation()), workerConfiguration(job.action()),
				null);
	}

	private static ClientOverrideConfiguration workerConfiguration(DatasetPublicationWorkerAction action) {
		var configuration = ClientOverrideConfiguration.builder();
		if (action != DatasetPublicationWorkerAction.VERIFY) {
			configuration.apiCallAttemptTimeout(Duration.ofSeconds(2)).apiCallTimeout(Duration.ofSeconds(5));
		}
		return configuration.build();
	}

	private static String digest(byte[] bytes) {
		return "sha256:" + HexFormat.of().formatHex(sha256().digest(bytes));
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException failure) {
			throw new IllegalStateException(failure);
		}
	}

	private static boolean safeKey(String value) {
		return !value.isBlank() && !value.startsWith("/") && !value.contains("\\")
				&& java.util.Arrays.stream(value.split("/", -1))
					.noneMatch(part -> part.isBlank() || part.equals(".") || part.equals(".."));
	}

	private static String key(String prefix, String suffix) {
		return prefix.endsWith("/") ? prefix + suffix : prefix + "/" + suffix;
	}

	private static WorkerFailure mismatch() {
		return new WorkerFailure("DATASET_REMOTE_MANIFEST_MISMATCH", false);
	}

	private record ManifestObject(String objectKey, long byteCount, String sha256) {
	}

	private static final class WorkerFailure extends RuntimeException {

		private final String code;

		private final boolean retryable;

		private WorkerFailure(String code, boolean retryable) {
			this.code = code;
			this.retryable = retryable;
		}

	}

}
