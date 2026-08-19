package de.zorro909.skywright.backend.targetstorage;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.zorro909.skywright.backend.acceptance.SeaweedFsFixture;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

@Tag("real-service")
final class TargetStorageQualificationIT {

	private static final Set<String> HOP_BY_HOP_HEADERS = Set.of("connection", "content-length", "host",
			"transfer-encoding");

	@Test
	void exercisesEveryRequiredCapabilityAndCleansItsPrivatePrefix() throws Exception {
		var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test-key", "test-secret"));
		try (SeaweedFsFixture service = SeaweedFsFixture.start();
				S3AsyncClient administrator = S3AsyncClient.builder()
					.httpClientBuilder(NettyNioAsyncHttpClient.builder())
					.endpointOverride(service.endpoint())
					.region(Region.US_EAST_1)
					.credentialsProvider(credentials)
					.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
					.build()) {
			service.awaitReady(administrator);
			String bucket = "qualification-" + UUID.randomUUID();
			administrator.createBucket(CreateBucketRequest.builder().bucket(bucket).build()).join();

			TargetStorageCredentialAccess credentialAccess = (bindingId, bindingRevision, consumingRole) -> Optional
				.of(credentials);
			TargetStorageAssessment assessment = new S3TargetStorageQualificationProbe(Optional.of(credentialAccess))
				.qualify(new TargetStorageQualificationRequest(UUID.randomUUID(), TargetStoragePurpose.RUN_OUTPUT,
						bucket, 1, new TargetStorageConfiguration(service.endpoint(), "us-east-1", true, Map.of()),
						readyBindings()));

			assertThat(assessment.availability()).isEqualTo(CapabilityAvailability.AVAILABLE);
			assertThat(assessment.capabilities()).hasSize(19).allMatch(TargetStorageCapabilityResult::succeeded);
			assertThat(administrator.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build())
				.join()
				.contents()).isEmpty();
			assertThat(administrator.listMultipartUploads(ListMultipartUploadsRequest.builder().bucket(bucket).build())
				.join()
				.uploads()).isEmpty();
		}
	}

	@Test
	void selectiveProviderFailuresAreDetectedThroughTheProductionAssessmentBoundary() throws Exception {
		var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test-key", "test-secret"));
		try (SeaweedFsFixture service = SeaweedFsFixture.start();
				S3AsyncClient administrator = S3AsyncClient.builder()
					.httpClientBuilder(NettyNioAsyncHttpClient.builder())
					.endpointOverride(service.endpoint())
					.region(Region.US_EAST_1)
					.credentialsProvider(credentials)
					.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
					.build()) {
			service.awaitReady(administrator);
			for (FailureMode mode : FailureMode.values()) {
				String bucket = "qf-" + mode.ordinal() + '-' + UUID.randomUUID().toString().replace("-", "");
				administrator.createBucket(CreateBucketRequest.builder().bucket(bucket).build()).join();
				try (QualificationProxy proxy = QualificationProxy.start(service.endpoint(), mode)) {
					TargetStorageAssessment assessment = new S3TargetStorageQualificationProbe(
							Optional.of((bindingId, bindingRevision, consumingRole) -> Optional.of(credentials)))
						.qualify(request(bucket, proxy.endpoint()));

					assertThat(assessment.availability()).as(mode.name())
						.isEqualTo(CapabilityAvailability.INCOMPATIBLE);
					assertThat(assessment.capabilities()).as(mode.name())
						.filteredOn(result -> result.capability().equals(mode.capability))
						.singleElement()
						.satisfies(result -> assertThat(result.succeeded()).isFalse());
				}
				cleanup(administrator, bucket);
			}

			TargetStorageAssessment unavailable = new S3TargetStorageQualificationProbe(
					Optional.of((bindingId, bindingRevision, consumingRole) -> Optional.empty()))
				.qualify(request("qualification-unavailable", service.endpoint()));
			assertThat(unavailable.availability()).isEqualTo(CapabilityAvailability.TRANSIENTLY_UNAVAILABLE);
			assertThat(unavailable.capabilities())
				.allMatch(result -> "credential-projection-unavailable".equals(result.failureCode()));
		}
	}

	private static TargetStorageQualificationRequest request(String bucket, URI endpoint) {
		return new TargetStorageQualificationRequest(UUID.randomUUID(), TargetStoragePurpose.RUN_OUTPUT, bucket, 1,
				new TargetStorageConfiguration(endpoint, "us-east-1", true, Map.of()), readyBindings());
	}

	private static void cleanup(S3AsyncClient administrator, String bucket) {
		administrator.listMultipartUploads(ListMultipartUploadsRequest.builder().bucket(bucket).build())
			.join()
			.uploads()
			.forEach(upload -> administrator
				.abortMultipartUpload(AbortMultipartUploadRequest.builder()
					.bucket(bucket)
					.key(upload.key())
					.uploadId(upload.uploadId())
					.build())
				.join());
		administrator.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build())
			.join()
			.contents()
			.forEach(object -> administrator
				.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(object.key()).build())
				.join());
	}

	private static List<TargetStorageBinding> readyBindings() {
		return java.util.Arrays.stream(TargetStorageRole.values())
			.map(role -> new TargetStorageBinding(role, UUID.randomUUID(), 1, BindingReadiness.READY))
			.toList();
	}

	private enum FailureMode {

		IGNORED_CONDITIONAL("conditional-create"), DELAYED_LISTING("list-after-write"),
		ROLE_ACCESS_DENIED("put-object"), CLEANUP_FAILURE("cleanup");

		private final String capability;

		FailureMode(String capability) {
			this.capability = capability;
		}

	}

	private record QualificationProxy(HttpServer server, URI upstream, FailureMode mode,
			AtomicInteger deleteRequests) implements AutoCloseable {

		static QualificationProxy start(URI upstream, FailureMode mode) throws Exception {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			QualificationProxy proxy = new QualificationProxy(server, upstream, mode, new AtomicInteger());
			server.createContext("/", proxy::forward);
			server.start();
			return proxy;
		}

		URI endpoint() {
			return URI.create("http://127.0.0.1:" + this.server.getAddress().getPort());
		}

		private void forward(HttpExchange exchange) {
			try {
				if (this.mode == FailureMode.ROLE_ACCESS_DENIED) {
					respond(exchange, 403,
							"<Error><Code>AccessDenied</Code><Message>denied by selective fake</Message></Error>"
								.getBytes(StandardCharsets.UTF_8),
							Map.of("Content-Type", List.of("application/xml")));
					return;
				}
				if (this.mode == FailureMode.CLEANUP_FAILURE && exchange.getRequestMethod().equals("DELETE")
						&& this.deleteRequests.incrementAndGet() > 1) {
					respond(exchange, 403, "<Error><Code>AccessDenied</Code><Message>cleanup denied</Message></Error>"
						.getBytes(StandardCharsets.UTF_8), Map.of("Content-Type", List.of("application/xml")));
					return;
				}
				URI target = this.upstream.resolve(exchange.getRequestURI().toString());
				HttpRequest.Builder request = HttpRequest.newBuilder(target);
				exchange.getRequestHeaders().forEach((name, values) -> {
					if (!HOP_BY_HOP_HEADERS.contains(name.toLowerCase())
							&& !(this.mode == FailureMode.IGNORED_CONDITIONAL
									&& (name.equalsIgnoreCase("If-None-Match") || name.equalsIgnoreCase("If-Match")))) {
						values.forEach(value -> request.header(name, value));
					}
				});
				byte[] requestBody = exchange.getRequestBody().readAllBytes();
				request.method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(requestBody));
				HttpResponse<byte[]> response = HttpClient.newHttpClient()
					.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
				byte[] body = response.body();
				if (this.mode == FailureMode.DELAYED_LISTING && exchange.getRequestURI().getRawQuery() != null
						&& exchange.getRequestURI().getRawQuery().contains("list-type=2")) {
					body = new String(body, StandardCharsets.UTF_8).replaceAll("(?s)<Contents>.*?</Contents>", "")
						.getBytes(StandardCharsets.UTF_8);
				}
				respond(exchange, response.statusCode(), body, response.headers().map());
			}
			catch (Exception failure) {
				try {
					exchange.close();
				}
				catch (RuntimeException ignored) {
					// The client will classify the closed exchange as an unavailable
					// fake.
				}
			}
		}

		private static void respond(HttpExchange exchange, int status, byte[] body, Map<String, List<String>> headers)
				throws java.io.IOException {
			headers.forEach((name, values) -> {
				if (!HOP_BY_HOP_HEADERS.contains(name.toLowerCase())) {
					values.forEach(value -> exchange.getResponseHeaders().add(name, value));
				}
			});
			if (exchange.getRequestMethod().equals("HEAD")) {
				exchange.sendResponseHeaders(status, -1);
			}
			else {
				exchange.sendResponseHeaders(status, body.length);
				exchange.getResponseBody().write(body);
			}
			exchange.close();
		}

		@Override
		public void close() {
			this.server.stop(0);
		}

	}

}
