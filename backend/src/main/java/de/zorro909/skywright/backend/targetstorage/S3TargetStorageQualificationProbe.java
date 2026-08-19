package de.zorro909.skywright.backend.targetstorage;

import de.zorro909.skywright.backend.runstore.RunStoreS3Compatibility;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.ListPartsResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

@Component
final class S3TargetStorageQualificationProbe implements TargetStorageQualificationProbe {

	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

	private static final Duration QUALIFICATION_TIMEOUT = Duration.ofMinutes(2);

	private final Optional<TargetStorageCredentialAccess> credentials;

	S3TargetStorageQualificationProbe(Optional<TargetStorageCredentialAccess> credentials) {
		this.credentials = credentials;
	}

	@Override
	public TargetStorageAssessment qualify(TargetStorageQualificationRequest request) {
		Instant started = Instant.now();
		TargetStorageBinding binding = request.bindings()
			.stream()
			.filter(value -> value.role() == TargetStorageRole.BACKEND && value.readiness() == BindingReadiness.READY)
			.findFirst()
			.orElse(null);
		if (binding == null) {
			return S3TargetStorageQualificationProbe.unavailable(request, started, "credential-binding-not-ready",
					"A ready backend Credential Binding is required");
		}
		AwsCredentialsProvider provider = this.credentials
			.flatMap(access -> access.credentials(binding.bindingId(), binding.bindingRevision(), "backend"))
			.orElse(null);
		if (provider == null) {
			return S3TargetStorageQualificationProbe.unavailable(request, started, "credential-projection-unavailable",
					"The backend Credential Projection is temporarily unavailable");
		}
		return S3TargetStorageQualificationProbe.exerciseWithinDeadline(request, provider, started);
	}

	private static TargetStorageAssessment exerciseWithinDeadline(TargetStorageQualificationRequest request,
			AwsCredentialsProvider credentials, Instant started) {
		CompletableFuture<TargetStorageAssessment> result = new CompletableFuture<>();
		Thread worker = Thread.ofVirtual().name("target-storage-qualification").start(() -> {
			try {
				result.complete(S3TargetStorageQualificationProbe.exercise(request, credentials, started));
			}
			catch (Throwable failure) {
				result.completeExceptionally(failure);
			}
		});
		try {
			return result.get(QUALIFICATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException failure) {
			worker.interrupt();
			return S3TargetStorageQualificationProbe.unavailable(request, started, "qualification-timeout",
					"Qualification exceeded its bounded execution time");
		}
		catch (InterruptedException failure) {
			worker.interrupt();
			Thread.currentThread().interrupt();
			return S3TargetStorageQualificationProbe.unavailable(request, started, "qualification-interrupted",
					"Qualification was interrupted");
		}
		catch (ExecutionException failure) {
			if (failure.getCause() instanceof RuntimeException runtimeFailure) {
				throw runtimeFailure;
			}
			throw new IllegalStateException("Target Storage qualification failed", failure.getCause());
		}
	}

	private static TargetStorageAssessment exercise(TargetStorageQualificationRequest request,
			AwsCredentialsProvider credentials, Instant started) {
		String prefix = ".skywright-qualification/" + UUID.randomUUID() + "/";
		String objectKey = prefix + "atomic-object";
		String multipartKey = prefix + "multipart-object";
		String abortedKey = prefix + "aborted-object";
		byte[] content = "skywright-target-storage-qualification".getBytes(StandardCharsets.UTF_8);
		byte[] replacementContent = "skywright-target-storage-qualification-replacement"
			.getBytes(StandardCharsets.UTF_8);
		String checksum = Base64.getEncoder().encodeToString(S3TargetStorageQualificationProbe.sha256(content));
		ResultCollector results = new ResultCollector();
		S3Configuration s3Configuration = RunStoreS3Compatibility
			.configuration(request.configuration().pathStyleAccess(), request.configuration().compatibilityOptions());
		ClientOverrideConfiguration deadlines = ClientOverrideConfiguration.builder()
			.apiCallTimeout(REQUEST_TIMEOUT)
			.apiCallAttemptTimeout(REQUEST_TIMEOUT)
			.build();
		try (S3AsyncClient client = S3AsyncClient.builder()
			.endpointOverride(request.configuration().endpoint())
			.region(Region.of(request.configuration().region()))
			.credentialsProvider(credentials)
			.httpClientBuilder(NettyNioAsyncHttpClient.builder()
				.connectionTimeout(Duration.ofSeconds(5))
				.readTimeout(REQUEST_TIMEOUT)
				.writeTimeout(REQUEST_TIMEOUT))
			.serviceConfiguration(s3Configuration)
			.requestChecksumCalculation(
					RunStoreS3Compatibility.checksumCalculation(request.configuration().compatibilityOptions()))
			.overrideConfiguration(deadlines)
			.build();
				S3Presigner presigner = S3Presigner.builder()
					.endpointOverride(request.configuration().endpoint())
					.region(Region.of(request.configuration().region()))
					.credentialsProvider(credentials)
					.serviceConfiguration(s3Configuration)
					.build();) {
			results
				.check("put-object",
						() -> client.putObject(PutObjectRequest.builder()
							.bucket(request.bucket())
							.key(objectKey)
							.ifNoneMatch("*")
							.metadata(Map.of("skywright-sha256", checksum, "skywright-purpose", "qualification"))
							.build(), AsyncRequestBody.fromBytes(content)).join());
			results.check("conditional-create",
					() -> S3TargetStorageQualificationProbe.requirePreconditionFailure(() -> client.putObject(
							PutObjectRequest.builder().bucket(request.bucket()).key(objectKey).ifNoneMatch("*").build(),
							AsyncRequestBody.fromBytes(content))
						.join()));
			AtomicReference<String> etag = new AtomicReference<>();
			results.check("metadata-preservation", () -> {
				HeadObjectResponse head = client
					.headObject(HeadObjectRequest.builder().bucket(request.bucket()).key(objectKey).build())
					.join();
				etag.set(head.eTag());
				if (!checksum.equals(head.metadata().get("skywright-sha256"))) {
					throw new IllegalStateException("Skywright checksum metadata was not preserved");
				}
			});
			results.check("checksum-preservation", () -> {
				ResponseBytes<?> body = client
					.getObject(GetObjectRequest.builder().bucket(request.bucket()).key(objectKey).build(),
							AsyncResponseTransformer.toBytes())
					.join();
				if (!Arrays.equals(content, body.asByteArray())) {
					throw new IllegalStateException("Object checksum did not survive retrieval");
				}
			});
			results.check("ranged-read", () -> {
				ResponseBytes body = client.getObject(
						GetObjectRequest.builder().bucket(request.bucket()).key(objectKey).range("bytes=0-8").build(),
						AsyncResponseTransformer.toBytes())
					.join();
				if (body.asByteArray().length != 9) {
					throw new IllegalStateException("Ranged read returned the wrong byte count");
				}
			});
			results.check("read-after-write",
					() -> client.headObject(HeadObjectRequest.builder().bucket(request.bucket()).key(objectKey).build())
						.join());
			results.check("list-after-write", () -> S3TargetStorageQualificationProbe.requireListed(client,
					request.bucket(), prefix, objectKey, true));
			results.check("list-objects",
					() -> client
						.listObjectsV2(ListObjectsV2Request.builder().bucket(request.bucket()).prefix(prefix).build())
						.join());
			results.check("conditional-replace", () -> {
				if (etag.get() == null) {
					throw new IllegalStateException("Object ETag is unavailable");
				}
				client
					.putObject(PutObjectRequest.builder()
						.bucket(request.bucket())
						.key(objectKey)
						.ifMatch(etag.get())
						.metadata(Map.of("skywright-sha256", checksum, "skywright-purpose", "qualification"))
						.build(), AsyncRequestBody.fromBytes(replacementContent))
					.join();
				S3TargetStorageQualificationProbe.requirePreconditionFailure(() -> client.putObject(
						PutObjectRequest.builder().bucket(request.bucket()).key(objectKey).ifMatch(etag.get()).build(),
						AsyncRequestBody.fromBytes(content))
					.join());
			});
			results.check("get-presigning", () -> {
				var presigned = presigner.presignGetObject(GetObjectPresignRequest.builder()
					.signatureDuration(Duration.ofMinutes(1L))
					.getObjectRequest(GetObjectRequest.builder().bucket(request.bucket()).key(objectKey).build())
					.build());
				try {
					HttpResponse<byte[]> response = HttpClient.newBuilder()
						.connectTimeout(Duration.ofSeconds(5))
						.build()
						.send(HttpRequest.newBuilder(presigned.url().toURI()).timeout(REQUEST_TIMEOUT).GET().build(),
								HttpResponse.BodyHandlers.ofByteArray());
					if (response.statusCode() == 429 || response.statusCode() >= 500) {
						throw new TransientQualificationException("Presigned GET is temporarily unavailable");
					}
					if (response.statusCode() != 200 || !Arrays.equals(replacementContent, response.body())) {
						throw new IllegalStateException("Presigned GET did not retrieve the expected object");
					}
				}
				catch (InterruptedException failure) {
					Thread.currentThread().interrupt();
					throw new TransientQualificationException("Presigned GET was interrupted", failure);
				}
				catch (java.io.IOException failure) {
					throw new TransientQualificationException("Presigned GET could not be executed", failure);
				}
				catch (java.net.URISyntaxException failure) {
					throw new IllegalStateException("Presigned GET URL was invalid", failure);
				}
			});
			AtomicReference<String> upload = new AtomicReference<>();
			AtomicReference<String> partEtag = new AtomicReference<>();
			results.check("multipart-create",
					() -> upload.set(client.createMultipartUpload(
							CreateMultipartUploadRequest.builder().bucket(request.bucket()).key(multipartKey).build())
						.join()
						.uploadId()));
			results.check("multipart-upload",
					() -> partEtag.set(
							client
								.uploadPart(UploadPartRequest.builder()
									.bucket(request.bucket())
									.key(multipartKey)
									.uploadId(S3TargetStorageQualificationProbe.required(upload.get(),
											"multipart upload"))
									.partNumber(1)
									.build(), AsyncRequestBody.fromBytes(content))
								.join()
								.eTag()));
			results.check("multipart-list-parts", () -> {
				ListPartsResponse listedParts = client
					.listParts(ListPartsRequest.builder()
						.bucket(request.bucket())
						.key(multipartKey)
						.uploadId(S3TargetStorageQualificationProbe.required(upload.get(), "multipart upload"))
						.build())
					.join();
				if (listedParts.parts().stream().noneMatch(part -> part.partNumber() == 1)) {
					throw new IllegalStateException("Uploaded multipart part was not listed");
				}
			});
			results.check("list-multipart-uploads", () -> {
				ListMultipartUploadsResponse listedUploads = client
					.listMultipartUploads(
							ListMultipartUploadsRequest.builder().bucket(request.bucket()).prefix(prefix).build())
					.join();
				if (listedUploads.uploads()
					.stream()
					.noneMatch(candidate -> candidate.key().equals(multipartKey)
							&& candidate.uploadId().equals(upload.get()))) {
					throw new IllegalStateException("Multipart upload was not listed");
				}
			});
			results.check("multipart-complete",
					() -> client
						.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
							.bucket(request.bucket())
							.key(multipartKey)
							.uploadId(S3TargetStorageQualificationProbe.required(upload.get(), "multipart upload"))
							.multipartUpload(CompletedMultipartUpload.builder()
								.parts(CompletedPart.builder()
									.partNumber(1)
									.eTag(S3TargetStorageQualificationProbe.required(partEtag.get(), "part ETag"))
									.build())
								.build())
							.build())
						.join());
			AtomicReference<String> abortUpload = new AtomicReference<>();
			results.check("multipart-abort", () -> {
				abortUpload.set(client
					.createMultipartUpload(
							CreateMultipartUploadRequest.builder().bucket(request.bucket()).key(abortedKey).build())
					.join()
					.uploadId());
				client
					.abortMultipartUpload(AbortMultipartUploadRequest.builder()
						.bucket(request.bucket())
						.key(abortedKey)
						.uploadId(abortUpload.get())
						.build())
					.join();
			});
			results.check("delete-object",
					() -> client
						.deleteObject(DeleteObjectRequest.builder().bucket(request.bucket()).key(objectKey).build())
						.join());
			results.check("list-after-delete", () -> S3TargetStorageQualificationProbe.requireListed(client,
					request.bucket(), prefix, objectKey, false));
			results.check("cleanup", () -> {
				S3TargetStorageQualificationProbe.cleanup(client, request.bucket(), prefix);
				S3TargetStorageQualificationProbe.cleanup(client, request.bucket(), prefix);
			});
		}
		catch (RuntimeException failure) {
			results.fillMissing("qualification-initialization-failed",
					S3TargetStorageQualificationProbe.safeSummary(failure));
		}
		results.fillMissing("capability-not-exercised", "A prerequisite capability failed");
		CapabilityAvailability availability = results.succeeded() ? CapabilityAvailability.AVAILABLE
				: (results.incompatibleFailure() ? CapabilityAvailability.INCOMPATIBLE
						: CapabilityAvailability.TRANSIENTLY_UNAVAILABLE);
		return new TargetStorageAssessment(UUID.randomUUID(), request.configurationRevision(), started, Instant.now(),
				availability, request.bindings(), results.results());
	}

	private static void cleanup(S3AsyncClient client, String bucket, String prefix) {
		ListMultipartUploadsResponse uploads = client
			.listMultipartUploads(ListMultipartUploadsRequest.builder().bucket(bucket).prefix(prefix).build())
			.join();
		uploads.uploads()
			.forEach(upload -> client
				.abortMultipartUpload(AbortMultipartUploadRequest.builder()
					.bucket(bucket)
					.key(upload.key())
					.uploadId(upload.uploadId())
					.build())
				.join());
		ListObjectsV2Response objects = client
			.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build())
			.join();
		objects.contents()
			.forEach(object -> client
				.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(object.key()).build())
				.join());
		if (!client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build())
			.join()
			.contents()
			.isEmpty()) {
			throw new IllegalStateException("Qualification cleanup left objects behind");
		}
		if (!client.listMultipartUploads(ListMultipartUploadsRequest.builder().bucket(bucket).prefix(prefix).build())
			.join()
			.uploads()
			.isEmpty()) {
			throw new IllegalStateException("Qualification cleanup left multipart uploads behind");
		}
	}

	private static void requireListed(S3AsyncClient client, String bucket, String prefix, String key,
			boolean expected) {
		boolean listed = client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build())
			.join()
			.contents()
			.stream()
			.anyMatch(object -> object.key().equals(key));
		if (listed != expected) {
			throw new IllegalStateException(
					expected ? "Object was not immediately listed" : "Deleted object remained listed");
		}
	}

	private static void requirePreconditionFailure(Runnable operation) {
		try {
			operation.run();
		}
		catch (RuntimeException failure) {
			S3Exception s3;
			Throwable cause = failure;
			while (cause.getCause() != null) {
				cause = cause.getCause();
			}
			if (cause instanceof S3Exception && (s3 = (S3Exception) cause).statusCode() == 412) {
				return;
			}
			throw failure;
		}
		throw new IllegalStateException("Conditional creation was not enforced");
	}

	private static String required(String value, String label) {
		if (value == null) {
			throw new IllegalStateException(label + " is unavailable");
		}
		return value;
	}

	private static TargetStorageAssessment unavailable(TargetStorageQualificationRequest request, Instant started,
			String code, String summary) {
		ResultCollector results = new ResultCollector();
		results.fillMissing(code, summary);
		return new TargetStorageAssessment(UUID.randomUUID(), request.configurationRevision(), started, Instant.now(),
				CapabilityAvailability.TRANSIENTLY_UNAVAILABLE, request.bindings(), results.results());
	}

	private static String safeSummary(Throwable failure) {
		String simpleName = failure.getClass().getSimpleName();
		return simpleName.isBlank() ? "The storage operation failed"
				: "The storage operation failed (" + simpleName + ")";
	}

	private static byte[] sha256(byte[] content) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(content);
		}
		catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is unavailable", impossible);
		}
	}

	private static final class TransientQualificationException extends RuntimeException {

		private TransientQualificationException(String message) {
			super(message);
		}

		private TransientQualificationException(String message, Throwable cause) {
			super(message, cause);
		}

	}

	private static final class ResultCollector {

		private final Map<String, TargetStorageCapabilityResult> results = new LinkedHashMap<>();

		private boolean incompatibleFailure;

		private ResultCollector() {
		}

		void check(String capability, Runnable operation) {
			try {
				operation.run();
				this.results.put(capability, TargetStorageCapabilityResult.success(capability));
			}
			catch (RuntimeException failure) {
				boolean transientOutage = ResultCollector.isTransient(failure);
				this.incompatibleFailure |= !transientOutage;
				this.results.put(capability,
						TargetStorageCapabilityResult.failure(capability,
								transientOutage ? "transient-storage-outage" : "capability-failed",
								S3TargetStorageQualificationProbe.safeSummary(failure), Map.of()));
			}
		}

		boolean incompatibleFailure() {
			return this.incompatibleFailure;
		}

		private static boolean isTransient(Throwable failure) {
			for (Throwable current = failure; current != null; current = current.getCause()) {
				S3Exception serviceFailure;
				if (current instanceof SdkClientException || current instanceof TransientQualificationException) {
					return true;
				}
				if (!(current instanceof S3Exception) || (serviceFailure = (S3Exception) current).statusCode() != 429
						&& serviceFailure.statusCode() < 500)
					continue;
				return true;
			}
			return false;
		}

		void fillMissing(String code, String summary) {
			TargetStorageCapabilities.REQUIRED.forEach(capability -> this.results.putIfAbsent(capability,
					TargetStorageCapabilityResult.failure(capability, code, summary, Map.of())));
		}

		boolean succeeded() {
			return TargetStorageCapabilities.isCompleteSuccess(List.copyOf(this.results.values()));
		}

		List<TargetStorageCapabilityResult> results() {
			List<TargetStorageCapabilityResult> ordered = new ArrayList<>();
			TargetStorageCapabilities.REQUIRED.forEach(capability -> ordered.add(this.results.get(capability)));
			return List.copyOf(ordered);
		}

	}

}
