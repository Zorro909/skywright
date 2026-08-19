package de.zorro909.skywright.backend.targetstorage;

import de.zorro909.skywright.backend.runstore.RunStoreS3CapabilityFloor;
import java.nio.charset.StandardCharsets;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
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
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

@Component
@Primary
@ConditionalOnBean(value = { TargetStorageCredentialAccess.class })
final class S3TargetStorageQualificationProbe implements TargetStorageQualificationProbe {

	private final TargetStorageCredentialAccess credentials;

	S3TargetStorageQualificationProbe(TargetStorageCredentialAccess credentials) {
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
			.credentials(binding.bindingId(), binding.bindingRevision(), "backend")
			.orElse(null);
		if (provider == null) {
			return S3TargetStorageQualificationProbe.unavailable(request, started, "credential-projection-unavailable",
					"The backend Credential Projection is temporarily unavailable");
		}
		return S3TargetStorageQualificationProbe.exercise(request, provider, started);
	}

	private static TargetStorageAssessment exercise(TargetStorageQualificationRequest request,
			AwsCredentialsProvider credentials, Instant started) {
		String prefix = ".skywright-qualification/" + String.valueOf(UUID.randomUUID()) + "/";
		String objectKey = prefix + "atomic-object";
		String multipartKey = prefix + "multipart-object";
		String abortedKey = prefix + "aborted-object";
		byte[] content = "skywright-target-storage-qualification".getBytes(StandardCharsets.UTF_8);
		String checksum = Base64.getEncoder().encodeToString(S3TargetStorageQualificationProbe.sha256(content));
		ResultCollector results = new ResultCollector();
		S3Configuration s3Configuration = S3Configuration.builder()
			.pathStyleAccessEnabled(request.configuration().pathStyleAccess())
			.chunkedEncodingEnabled(optionEnabled(request, "chunkedEncoding", false))
			.build();
		try (S3AsyncClient client = S3AsyncClient.builder()
			.endpointOverride(request.configuration().endpoint())
			.region(Region.of(request.configuration().region()))
			.credentialsProvider(credentials)
			.httpClientBuilder(NettyNioAsyncHttpClient.builder())
			.serviceConfiguration(s3Configuration)
			.requestChecksumCalculation(checksumCalculation(request))
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
				ResponseBytes body = client
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
				S3TargetStorageQualificationProbe.requirePreconditionFailure(
						() -> client
							.putObject(PutObjectRequest.builder()
								.bucket(request.bucket())
								.key(objectKey)
								.ifMatch("\"skywright-nonmatching-etag\"")
								.build(), AsyncRequestBody.fromBytes(content))
							.join());
				client
					.putObject(PutObjectRequest.builder()
						.bucket(request.bucket())
						.key(objectKey)
						.ifMatch(etag.get())
						.metadata(Map.of("skywright-sha256", checksum, "skywright-purpose", "qualification"))
						.build(), AsyncRequestBody.fromBytes(content))
					.join();
			});
			results.check("get-presigning",
					() -> presigner.presignGetObject(GetObjectPresignRequest.builder()
						.signatureDuration(Duration.ofMinutes(1L))
						.getObjectRequest(GetObjectRequest.builder().bucket(request.bucket()).key(objectKey).build())
						.build()));
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
			results
				.check("multipart-list-parts",
						() -> client.listParts(ListPartsRequest.builder()
							.bucket(request.bucket())
							.key(multipartKey)
							.uploadId(S3TargetStorageQualificationProbe.required(upload.get(), "multipart upload"))
							.build()).join());
			results
				.check("list-multipart-uploads",
						() -> client.listMultipartUploads(
								ListMultipartUploadsRequest.builder().bucket(request.bucket()).prefix(prefix).build())
							.join());
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
			results.check("cleanup", () -> S3TargetStorageQualificationProbe.cleanup(client, request.bucket(), prefix));
		}
		catch (RuntimeException failure) {
			results.fillMissing("qualification-initialization-failed",
					S3TargetStorageQualificationProbe.safeSummary(failure));
		}
		results.fillMissing("capability-not-exercised", "A prerequisite capability failed");
		CapabilityAvailability availability = results.succeeded() ? CapabilityAvailability.AVAILABLE
				: (results.transientFailure() ? CapabilityAvailability.TRANSIENTLY_UNAVAILABLE
						: CapabilityAvailability.INCOMPATIBLE);
		return new TargetStorageAssessment(UUID.randomUUID(), request.configurationRevision(), started, Instant.now(),
				availability, request.bindings().stream().map(TargetStorageBindingRevision::from).toList(),
				results.results());
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
			Throwable cause = failure;
			while (cause.getCause() != null) {
				cause = cause.getCause();
			}
			if (cause instanceof S3Exception s3 && s3.statusCode() == 412) {
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

	private static boolean optionEnabled(TargetStorageQualificationRequest request, String name, boolean defaultValue) {
		String value = request.configuration().compatibilityOptions().get(name);
		return value == null ? defaultValue : "enabled".equals(value);
	}

	private static RequestChecksumCalculation checksumCalculation(TargetStorageQualificationRequest request) {
		return "when-supported".equals(request.configuration().compatibilityOptions().get("checksumCalculation"))
				? RequestChecksumCalculation.WHEN_SUPPORTED : RequestChecksumCalculation.WHEN_REQUIRED;
	}

	private static TargetStorageAssessment unavailable(TargetStorageQualificationRequest request, Instant started,
			String code, String summary) {
		ResultCollector results = new ResultCollector();
		results.fillMissing(code, summary);
		return new TargetStorageAssessment(UUID.randomUUID(), request.configurationRevision(), started, Instant.now(),
				CapabilityAvailability.TRANSIENTLY_UNAVAILABLE,
				request.bindings().stream().map(TargetStorageBindingRevision::from).toList(), results.results());
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

	private static final class ResultCollector {

		private final Map<String, TargetStorageCapabilityResult> results = new LinkedHashMap<>();

		private boolean transientFailure;

		private ResultCollector() {
		}

		void check(String capability, Runnable operation) {
			try {
				operation.run();
				this.results.put(capability, TargetStorageCapabilityResult.success(capability));
			}
			catch (RuntimeException failure) {
				boolean transientOutage = ResultCollector.isTransient(failure);
				this.transientFailure |= transientOutage;
				this.results.put(capability,
						TargetStorageCapabilityResult.failure(capability,
								transientOutage ? "transient-storage-outage" : "capability-failed",
								S3TargetStorageQualificationProbe.safeSummary(failure), Map.of()));
			}
		}

		boolean transientFailure() {
			return this.transientFailure;
		}

		private static boolean isTransient(Throwable failure) {
			for (Throwable current = failure; current != null; current = current.getCause()) {
				if (current instanceof SdkClientException) {
					return true;
				}
				if (current instanceof S3Exception serviceFailure
						&& (serviceFailure.statusCode() == 429 || serviceFailure.statusCode() >= 500)) {
					return true;
				}
			}
			return false;
		}

		void fillMissing(String code, String summary) {
			RunStoreS3CapabilityFloor.requiredCapabilities()
				.forEach(capability -> this.results.putIfAbsent(capability,
						TargetStorageCapabilityResult.failure(capability, code, summary, Map.of())));
		}

		boolean succeeded() {
			return this.results.size() == RunStoreS3CapabilityFloor.requiredCapabilities().size()
					&& this.results.values().stream().allMatch(TargetStorageCapabilityResult::succeeded);
		}

		List<TargetStorageCapabilityResult> results() {
			List<TargetStorageCapabilityResult> ordered = new ArrayList<>();
			RunStoreS3CapabilityFloor.requiredCapabilities()
				.forEach(capability -> ordered.add(this.results.get(capability)));
			return List.copyOf(ordered);
		}

	}

}
