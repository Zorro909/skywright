package de.zorro909.skywright.backend.runstore;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
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

	private final List<RunStoreOperationMeasurement> measurements = new CopyOnWriteArrayList<>();

	private final AtomicLong requestNumber = new AtomicLong();

	public S3RunStoreObjectStore(ResolvedTargetStorage target) {
		this(target, RunStoreOperationControl.defaults());
	}

	public S3RunStoreObjectStore(ResolvedTargetStorage target, RunStoreOperationControl control) {
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
			.httpClientBuilder(NettyNioAsyncHttpClient.builder())
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
	public List<RunStoreObject> list(String prefix) {
		List<RunStoreObject> result = new ArrayList<>();
		String continuation = null;
		do {
			checkCancellation();
			Instant started = Instant.now();
			ListObjectsV2Response page;
			try {
				page = this.client
					.listObjectsV2(ListObjectsV2Request.builder()
						.bucket(this.target.bucket())
						.prefix(prefix)
						.continuationToken(continuation)
						.build())
					.join();
				measure("ListObjectsV2", 0, "control", started, true);
			}
			catch (RuntimeException failure) {
				measure("ListObjectsV2", 0, "control", started, false);
				throw failure;
			}
			page.contents().forEach(item -> result.add(get(item.key())));
			continuation = page.isTruncated() ? page.nextContinuationToken() : null;
		}
		while (continuation != null);
		return List.copyOf(result);
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
	public RunStoreObject get(String key) {
		checkCancellation();
		Instant started = Instant.now();
		try {
			ResponseBytes<GetObjectResponse> response = this.client
				.getObject(GetObjectRequest.builder().bucket(this.target.bucket()).key(key).build(),
						AsyncResponseTransformer.toBytes())
				.join();
			measure("GetObject", response.asByteArray().length, "read", started, true);
			return new RunStoreObject(key, response.asByteArray(), response.response().contentType(),
					response.response().metadata());
		}
		catch (RuntimeException failure) {
			measure("GetObject", 0, "read", started, false);
			throw failure;
		}
	}

	@Override
	public URI presignGet(String key, int expiresInSeconds, String contentType, String filename) {
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
		return List.copyOf(this.measurements);
	}

	private void checkCancellation() {
		if (this.control.cancellationRequested().getAsBoolean()) {
			throw new RunStoreOperationCancelledException();
		}
	}

	private void measure(String operation, long bytes, String direction, Instant timestamp, boolean succeeded) {
		this.measurements
			.add(new RunStoreOperationMeasurement(operation, bytes, direction, this.requestNumber.incrementAndGet(),
					this.target.runId(), timestamp, this.target.storageId(), succeeded));
	}

	@Override
	public void close() {
		this.presigner.close();
		this.client.close();
	}

}
