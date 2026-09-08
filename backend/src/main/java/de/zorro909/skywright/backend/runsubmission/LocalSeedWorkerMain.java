package de.zorro909.skywright.backend.runsubmission;

import de.zorro909.skywright.backend.runstore.*;
import de.zorro909.skywright.backend.worker.TransferObjects;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.*;
import tools.jackson.databind.json.JsonMapper;

/**
 * Isolated bounded checkpoint Transfer Worker; never loads project code or tensor state.
 */
public final class LocalSeedWorkerMain {

	static final long MAX_BYTES = 32L * 1024 * 1024;

	private static final JsonMapper JSON = JsonMapper.builder().build();

	public record Credential(String accessKeyId, String secretAccessKey, String sessionToken) {
		AwsCredentialsProvider provider() {
			return StaticCredentialsProvider
				.create(sessionToken == null ? AwsBasicCredentials.create(accessKeyId, secretAccessKey)
						: AwsSessionCredentials.create(accessKeyId, secretAccessKey, sessionToken));
		}
	}

	public record Credentials(Credential source, Credential destination) {
	}

	public record Receipt(String key, long size, String digest, String predecessorRunId, String projectVersion) {
	}

	public static void main(String[] arguments) throws Exception {
		// The durable preparation lease outlives this hard process deadline.
		Thread.ofPlatform().daemon(true).start(() -> {
			try {
				Thread.sleep(240_000);
				Runtime.getRuntime().halt(74);
			}
			catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
		});
		if (arguments.length != 2)
			throw new IllegalArgumentException("Expected job and receipt files");
		var job = JSON.readValue(Path.of(arguments[0]).toFile(), LocalSeedWorkerJob.class);
		var credentials = JSON.readValue(System.in, Credentials.class);
		var result = transfer(job, credentials, Path.of(arguments[0]).getParent());
		JSON.writeValue(Path.of(arguments[1]).toFile(), result);
	}

	static Receipt transfer(LocalSeedWorkerJob job, Credentials credentials, Path directory) throws Exception {
		var reference = CheckpointReference.parse(job.checkpointReference());
		var source = resolved(job.source(), credentials.source(), job.projectId().toString(),
				job.predecessorRunId().toString());
		var protocol = new RunStoreProtocol(source.trainingProjectId(), source.runId());
		try (var objects = new S3RunStoreObjectStore(source,
				new RunStoreOperationControl(Duration.ofSeconds(30), () -> false));
				var destination = client(job.destination(), credentials.destination())) {
			String key = job.destinationKey();
			abortAbandoned(destination, job.destination().bucket(), key);
			// An acknowledgement may have been lost after immutable publication.
			try {
				var head = destination.headObject(b -> b.bucket(job.destination().bucket()).key(key)).join();
				verify(destination, job.destination().bucket(), key, head.contentLength(), reference.digest(),
						directory, job, reference.step());
				return new Receipt(key, head.contentLength(), reference.digest(), source.runId(), job.projectVersion());
			}
			catch (java.util.concurrent.CompletionException failure) {
				if (!(failure.getCause() instanceof S3Exception s3) || s3.statusCode() != 404)
					throw failure;
			}
			var access = new RunStoreAccess(protocol, objects);
			try (var staged = access.stageDownload(protocol.checkpointKey(reference.step(), reference.digest()),
					directory, MAX_BYTES)) {
				validateHeader(staged.path(), job, reference.step());
				var metadata = Map.of("skywright-schema", "v1", "skywright-kind", "checkpoint", "skywright-sha256",
						reference.digest(), "skywright-size", Long.toString(staged.descriptor().size()));
				var upload = destination
					.createMultipartUpload(b -> b.bucket(job.destination().bucket())
						.key(key)
						.contentType("application/octet-stream")
						.metadata(metadata))
					.join();
				boolean complete = false;
				try {
					var parts = new ArrayList<CompletedPart>();
					try (var stream = Files.newInputStream(staged.path())) {
						byte[] bytes;
						while ((bytes = stream.readNBytes(8 * 1024 * 1024)).length > 0) {
							int number = parts.size() + 1;
							var part = destination
								.uploadPart(b -> b.bucket(job.destination().bucket())
									.key(key)
									.uploadId(upload.uploadId())
									.partNumber(number), AsyncRequestBody.fromBytes(bytes))
								.join();
							parts.add(CompletedPart.builder().partNumber(number).eTag(part.eTag()).build());
						}
					}
					destination
						.completeMultipartUpload(b -> b.bucket(job.destination().bucket())
							.key(key)
							.uploadId(upload.uploadId())
							.ifNoneMatch("*")
							.multipartUpload(CompletedMultipartUpload.builder().parts(parts).build()))
						.join();
					complete = true;
				}
				finally {
					if (!complete)
						destination
							.abortMultipartUpload(
									b -> b.bucket(job.destination().bucket()).key(key).uploadId(upload.uploadId()))
							.join();
				}
				verify(destination, job.destination().bucket(), key, staged.descriptor().size(), reference.digest(),
						directory, job, reference.step());
				return new Receipt(key, staged.descriptor().size(), reference.digest(), source.runId(),
						job.projectVersion());
			}
		}
	}

	private static void validateHeader(Path path, LocalSeedWorkerJob job, long step) throws Exception {
		try (var stream = Files.newInputStream(path)) {
			byte[] prefix = stream.readNBytes(8);
			if (prefix.length != 8)
				throw new IllegalArgumentException("SEED_INVALID_HEADER");
			long size = java.nio.ByteBuffer.wrap(prefix).order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong();
			if (size < 2 || size > 16 * 1024 * 1024 || size > Files.size(path) - 8)
				throw new IllegalArgumentException("SEED_INVALID_HEADER");
			var metadata = JSON.readTree(stream.readNBytes((int) size)).path("__metadata__");
			var manifest = JSON.readTree(metadata.path("skywright.manifest").asText());
			if (!metadata.path("skywright.schema").asText().equals("checkpoint-v1")
					|| !manifest.path("schemaVersion").isInt() || manifest.path("schemaVersion").asInt() != 1
					|| !manifest.path("runId").asText().equals(job.predecessorRunId().toString())
					|| !manifest.path("projectVersion").asText().equals(job.projectVersion())
					|| !manifest.path("step").isIntegralNumber() || manifest.path("step").asLong() != step)
				throw new IllegalArgumentException("SEED_IDENTITY_MISMATCH");
		}
	}

	private static void verify(S3AsyncClient client, String bucket, String key, long size, String digest,
			Path directory, LocalSeedWorkerJob job, long step) throws Exception {
		if (size < 1 || size > MAX_BYTES)
			throw new IllegalArgumentException("SEED_SIZE_BUDGET");
		var body = client.getObject(b -> b.bucket(bucket).key(key), AsyncResponseTransformer.toBlockingInputStream())
			.join();
		Path copy = null;
		try {
			copy = Files.createTempFile(directory, "seed-verify-", ".checkpoint");
			try (var output = Files.newOutputStream(copy)) {
				var response = body.response();
				var metadata = response.metadata();
				if (response.contentLength() != size || !Long.toString(size).equals(metadata.get("skywright-size"))
						|| !digest.equals(metadata.get("skywright-sha256"))
						|| !"v1".equals(metadata.get("skywright-schema"))
						|| !"checkpoint".equals(metadata.get("skywright-kind")))
					throw new IllegalArgumentException("SEED_METADATA_MISMATCH");
				TransferObjects.verify(body, size, digest, output);
				output.flush();
				validateHeader(copy, job, step);
			}
		}
		finally {
			body.abort();
			if (copy != null)
				Files.deleteIfExists(copy);
		}
	}

	private static void abortAbandoned(S3AsyncClient client, String bucket, String key) {
		TransferObjects.abortUploads(client, bucket, key, key::equals, 100);
	}

	private static ResolvedTargetStorage resolved(LocalSeedWorkerJob.Storage storage, Credential credential,
			String project, String run) {
		return new ResolvedTargetStorage(storage.storageId(), storage.endpoint(), storage.bucket(),
				Region.of(storage.region()), storage.pathStyle(), storage.options(), credential.provider(), project,
				run, storage.bindingId(), storage.bindingRevision());
	}

	private static S3AsyncClient client(LocalSeedWorkerJob.Storage storage, Credential credential) {
		return TransferObjects.client(storage.endpoint(), storage.region(), credential.provider(), storage.pathStyle(),
				"enabled".equals(storage.options().get("chunkedEncoding")),
				software.amazon.awssdk.core.checksums.RequestChecksumCalculation.WHEN_REQUIRED,
				ClientOverrideConfiguration.builder().apiCallTimeout(Duration.ofSeconds(30)).build(),
				Duration.ofSeconds(30));
	}

}
