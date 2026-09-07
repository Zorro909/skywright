package de.zorro909.skywright.backend.runlog;

import de.zorro909.skywright.backend.runstore.BoundedS3Body;
import de.zorro909.skywright.backend.runstore.ResolvedTargetStorage;
import de.zorro909.skywright.backend.runstore.RunStoreProtocol;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionException;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.S3Exception;

final class S3ArchiveObjects implements ArchiveObjects {

	private final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(40);

	private final S3AsyncClient client;

	private final String bucket;

	private final String prefix;

	S3ArchiveObjects(ResolvedTargetStorage target) {
		bucket = target.bucket();
		prefix = new RunStoreProtocol(target.trainingProjectId(), target.runId()).runPrefix();
		client = S3AsyncClient.builder()
			.endpointOverride(target.endpoint())
			.region(target.region())
			.credentialsProvider(target.credentials())
			.serviceConfiguration(S3Configuration.builder()
				.pathStyleAccessEnabled(target.pathStyleAccess())
				.chunkedEncodingEnabled("enabled".equals(target.compatibilityOptions().get("chunkedEncoding")))
				.build())
			.requestChecksumCalculation(
					"when-supported".equals(target.compatibilityOptions().get("checksumCalculation"))
							? RequestChecksumCalculation.WHEN_SUPPORTED : RequestChecksumCalculation.WHEN_REQUIRED)
			.overrideConfiguration(ClientOverrideConfiguration.builder()
				.retryStrategy(b -> b.maxAttempts(1))
				.apiCallTimeout(Duration.ofSeconds(5))
				.apiCallAttemptTimeout(Duration.ofSeconds(5))
				.build())
			.build();
	}

	@Override
	public byte[] read(String relativeKey, int limit) {
		try {
			var response = client.getObject(b -> b.bucket(bucket).key(key(relativeKey)), new BoundedS3Body(limit))
				.join();
			byte[] body = response.asByteArray();
			var metadata = response.response().metadata();
			if (!"v1".equals(metadata.get("skywright-schema"))
					|| !Integer.toString(body.length).equals(metadata.get("skywright-size"))
					|| !RunLogArchive.digest(body).equals(metadata.get("skywright-sha256")))
				throw new IllegalStateException("ARCHIVE_OBJECT_INVALID");
			return body;
		}
		catch (CompletionException failure) {
			if (failure.getCause() instanceof S3Exception s3 && s3.statusCode() == 404)
				return null;
			throw failure;
		}
	}

	@Override
	public byte[] publish(String relativeKey, byte[] bytes, String kind) {
		if (!relativeKey.startsWith("skypilot/logs/") || bytes.length > RunLogArchive.CHUNK_BYTES)
			throw new IllegalArgumentException("Invalid archive publication");
		try {
			client
				.putObject(b -> b.bucket(bucket)
					.key(key(relativeKey))
					.ifNoneMatch("*")
					.contentType(kind.equals("raw") ? "application/octet-stream" : "application/json")
					.metadata(Map.of("skywright-schema", "v1", "skywright-kind", "run-log-" + kind, "skywright-size",
							Integer.toString(bytes.length), "skywright-sha256", RunLogArchive.digest(bytes))),
						AsyncRequestBody.fromBytes(bytes))
				.join();
		}
		catch (CompletionException failure) {
			if (!(failure.getCause() instanceof S3Exception s3) || s3.statusCode() != 412)
				throw failure;
		}
		byte[] published = read(relativeKey, RunLogArchive.CHUNK_BYTES);
		if (published == null)
			throw new IllegalStateException("ARCHIVE_PUBLICATION_UNCONFIRMED");
		return published;
	}

	private String key(String relative) {
		if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline)
			throw new IllegalStateException("ARCHIVE_CYCLE_DEADLINE");
		if (relative.startsWith("/") || relative.contains("..") || relative.indexOf('\0') >= 0)
			throw new IllegalArgumentException("Invalid archive object key");
		return prefix + relative;
	}

	@Override
	public java.util.List<String> keysAfter(String relative, String after, int limit) {
		if (limit < 1 || limit > 100)
			throw new IllegalArgumentException("Invalid archive listing limit");
		var page = client
			.listObjectsV2(b -> b.bucket(bucket)
				.prefix(key(relative))
				.maxKeys(limit)
				.startAfter(after == null ? null : key(after)))
			.join();
		if (page.contents().size() > limit)
			throw new IllegalStateException("ARCHIVE_LIST_INVALID");
		return page.contents().stream().map(item -> {
			if (!item.key().startsWith(key(relative)) || after != null && item.key().compareTo(key(after)) <= 0)
				throw new IllegalStateException("ARCHIVE_LIST_INVALID");
			return item.key().substring(prefix.length());
		}).toList();
	}

	@Override
	public void close() {
		client.close();
	}

}
