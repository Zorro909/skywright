package de.zorro909.skywright.backend.runstore;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.io.FilterInputStream;
import java.io.IOException;
import java.util.List;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/** AWS-provider adapter hidden behind the Java Run Store access module. */
public final class S3RunStoreObjectStore implements RunStoreObjectStore, AutoCloseable {

	private final ResolvedTargetStorage target;

	private final S3AsyncClient client;

	private final S3Presigner presigner;

	private final RunStoreOperationControl control;

	private final RunStoreMeasurements measurements;

	public S3RunStoreObjectStore(ResolvedTargetStorage target) {
		this(target, RunStoreOperationControl.defaults());
	}

	public S3RunStoreObjectStore(ResolvedTargetStorage target, RunStoreOperationControl control) {
		this(target, control, 256);
	}

	public S3RunStoreObjectStore(ResolvedTargetStorage target, RunStoreOperationControl control,
			int measurementCapacity) {
		this.measurements = new RunStoreMeasurements(target.runId(), target.storageId(), measurementCapacity);
		this.target = target;
		this.control = control;
		S3Configuration configuration = S3Configuration.builder()
			.pathStyleAccessEnabled(target.pathStyleAccess())
			.chunkedEncodingEnabled(optionEnabled(target, "chunkedEncoding", false))
			.build();
		ClientOverrideConfiguration deadlines = ClientOverrideConfiguration.builder()
			.apiCallTimeout(control.requestTimeout())
			.apiCallAttemptTimeout(control.requestTimeout())
			.build();
		this.client = S3AsyncClient.builder()
			.httpClientBuilder(NettyNioAsyncHttpClient.builder().readTimeout(control.requestTimeout()))
			.endpointOverride(target.endpoint())
			.region(target.region())
			.credentialsProvider(target.credentials())
			.serviceConfiguration(configuration)
			.requestChecksumCalculation(checksumCalculation(target))
			.overrideConfiguration(deadlines)
			.build();
		this.presigner = S3Presigner.builder()
			.endpointOverride(target.endpoint())
			.region(target.region())
			.credentialsProvider(target.credentials())
			.serviceConfiguration(configuration)
			.build();
	}

	@Override
	public RunStoreObjectPage list(String prefix, int limit, String continuation) {
		requireRunScope(prefix);
		if (limit < 1 || limit > 1000) {
			throw new IllegalArgumentException("page limit must be 1..1000");
		}
		checkCancellation();
		Instant started = Instant.now();
		try {
			ListObjectsV2Response page = this.client
				.listObjectsV2(ListObjectsV2Request.builder()
					.bucket(this.target.bucket())
					.prefix(prefix)
					.maxKeys(limit)
					.continuationToken(continuation)
					.build())
				.join();
			measure("ListObjectsV2", 0, "control", started, true);
			if (page.contents().size() > limit || (page.isTruncated()
					&& (page.nextContinuationToken() == null || page.nextContinuationToken().equals(continuation)))) {
				throw new RunStoreIntegrityException("RUN_STORE_INVALID_PAGE: provider exceeded page contract");
			}
			return new RunStoreObjectPage(page.contents()
				.stream()
				.map(item -> new RunStoreObjectPage.Entry(item.key(), item.size()))
				.toList(), page.isTruncated() ? page.nextContinuationToken() : null);
		}
		catch (RuntimeException failure) {
			measure("ListObjectsV2", 0, "control", started, false);
			throw failure;
		}
	}

	@Override
	public RunStoreObjectMetadata head(String key) {
		requireRunScope(key);
		checkCancellation();
		Instant started = Instant.now();
		try {
			HeadObjectResponse response = this.client
				.headObject(HeadObjectRequest.builder().bucket(this.target.bucket()).key(key).build())
				.join();
			measure("HeadObject", 0, "control", started, true);
			return new RunStoreObjectMetadata(key, response.contentLength(), response.contentType(),
					response.metadata());
		}
		catch (RuntimeException failure) {
			measure("HeadObject", 0, "control", started, false);
			throw failure;
		}
	}

	private static boolean optionEnabled(ResolvedTargetStorage target, String name, boolean defaultValue) {
		String value = target.compatibilityOptions().get(name);
		return value == null ? defaultValue : "enabled".equals(value);
	}

	private static RequestChecksumCalculation checksumCalculation(ResolvedTargetStorage target) {
		return "when-supported".equals(target.compatibilityOptions().get("checksumCalculation"))
				? RequestChecksumCalculation.WHEN_SUPPORTED : RequestChecksumCalculation.WHEN_REQUIRED;
	}

	@Override
	public RunStoreContent open(String key) {
		requireRunScope(key);
		checkCancellation();
		Instant started = Instant.now();
		try {
			ResponseInputStream<GetObjectResponse> response = this.client
				.getObject(GetObjectRequest.builder().bucket(this.target.bucket()).key(key).build(),
						AsyncResponseTransformer.toBlockingInputStream())
				.join();
			GetObjectResponse metadata = response.response();
			return new RunStoreContent(new RunStoreObjectMetadata(key, metadata.contentLength(), metadata.contentType(),
					metadata.metadata()), new FilterInputStream(response) {
						private long consumed;

						private boolean complete;

						private boolean closed;

						@Override
						public int read() throws IOException {
							byte[] single = new byte[1];
							return read(single, 0, 1) < 0 ? -1 : single[0] & 255;
						}

						@Override
						public int read(byte[] buffer, int offset, int length) throws IOException {
							checkCancellation();
							if (Duration.between(started, Instant.now()).compareTo(control.requestTimeout()) > 0) {
								throw new IOException("Run Store content deadline expired");
							}
							int count = in.read(buffer, offset, length);
							if (count < 0) {
								this.complete = true;
							}
							else {
								this.consumed += count;
							}
							return count;
						}

						@Override
						public void close() throws IOException {
							if (!this.closed) {
								this.closed = true;
								try {
									if (this.complete) {
										response.close();
									}
									else {
										response.abort();
									}
								}
								finally {
									measure("GetObject", this.consumed, "read", started, this.complete);
								}
							}
						}
					});
		}
		catch (RuntimeException failure) {
			measure("GetObject", 0, "read", started, false);
			throw failure;
		}
	}

	@Override
	public URI presignGet(String key, int expiresInSeconds, String contentType, String filename) {
		requireRunScope(key);
		checkCancellation();
		Instant started = Instant.now();
		GetObjectRequest get = GetObjectRequest.builder()
			.bucket(this.target.bucket())
			.key(key)
			.responseContentType(contentType)
			.responseContentDisposition("attachment; filename*=UTF-8''" + PercentCodec.encode(filename))
			.build();
		URI result = URI.create(
				this.presigner
					.presignGetObject(GetObjectPresignRequest.builder()
						.signatureDuration(Duration.ofSeconds(expiresInSeconds))
						.getObjectRequest(get)
						.build())
					.url()
					.toString());
		measure("PresignGetObject", 0, "control", started, true);
		return result;
	}

	public List<RunStoreOperationMeasurement> measurements() {
		return this.measurements.snapshot();
	}

	/** Transfer recent records and explicit overflow gaps to the usage consumer. */
	public RunStoreMeasurementBatch drainMeasurements() {
		return this.measurements.drain();
	}

	private void requireRunScope(String key) {
		String prefix = new RunStoreProtocol(this.target.trainingProjectId(), this.target.runId()).runPrefix();
		if (!key.startsWith(prefix)) {
			throw new RunStoreIntegrityException("RUN_STORE_WRONG_RUN: key is outside the resolved Run location");
		}
	}

	private void checkCancellation() {
		if (this.control.cancellationRequested().getAsBoolean()) {
			throw new RunStoreOperationCancelledException();
		}
	}

	private void measure(String operation, long bytes, String direction, Instant timestamp, boolean succeeded) {
		this.measurements.record(operation, bytes, direction, timestamp, succeeded);
	}

	@Override
	public void close() {
		this.presigner.close();
		this.client.close();
	}

}
