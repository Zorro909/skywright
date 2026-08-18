package de.zorro909.skywright.backend.runstore;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

class RunStoreS3IT {

	private static final String IMAGE = "docker.io/chrislusf/seaweedfs:4.42@sha256:"
			+ "f7cbc8bdbbf60a1aaba7d61784a3bdff3ec1e0657f6ad0b26d5b6ab2cd9d0dc6";

	@Test
	void javaAccessUsesTheSamePinnedSeaweedFsProtocol() throws Exception {
		try (SeaweedFs service = SeaweedFs.start()) {
			var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test-key", "test-secret"));
			String bucket = "skywright-" + UUID.randomUUID().toString();
			try (S3Client writer = S3Client.builder()
				.httpClientBuilder(UrlConnectionHttpClient.builder())
				.endpointOverride(service.endpoint())
				.region(Region.US_EAST_1)
				.credentialsProvider(credentials)
				.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
				.serviceConfiguration(
						S3Configuration.builder().pathStyleAccessEnabled(true).chunkedEncodingEnabled(false).build())
				.build()) {
				service.awaitReady(writer);
				writer.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
				RunStoreProtocol protocol = new RunStoreProtocol("project", "run");
				String key = protocol.artifactKey("123e4567-e89b-12d3-a456-426614174000", 1, "reports/final.txt");
				byte[] body = "finished".getBytes(StandardCharsets.UTF_8);
				writer.putObject(PutObjectRequest.builder()
					.bucket(bucket)
					.key(key)
					.contentType("application/octet-stream")
					.metadata(Map.of("skywright-sha256", sha256(body), "skywright-size", Integer.toString(body.length),
							"skywright-kind", "artifact", "skywright-schema", "v1", "skywright-media-type",
							"application/octet-stream"))
					.build(), RequestBody.fromBytes(body));

				ResolvedTargetStorage target = new ResolvedTargetStorage("seaweedfs", service.endpoint(), bucket,
						Region.US_EAST_1, true, credentials, "project", "run");
				try (S3RunStoreObjectStore objects = new S3RunStoreObjectStore(target)) {
					RunStoreAccess access = new RunStoreAccess(protocol, objects);
					assertThat(access.listOutputs()).extracting(RunStoreOutput::name)
						.containsExactly("reports/final.txt");
					URI download = access.presignDownload(key, 60);
					HttpResponse<byte[]> response = HttpClient.newHttpClient()
						.send(HttpRequest.newBuilder(download).timeout(Duration.ofSeconds(5)).build(),
								HttpResponse.BodyHandlers.ofByteArray());
					assertThat(response.body()).isEqualTo(body);
					assertThat(objects.measurements()).isNotEmpty();
				}
			}
		}
	}

	private static String sha256(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}

	private record SeaweedFs(String container, URI endpoint) implements AutoCloseable {

		static SeaweedFs start() throws Exception {
			int port;
			try (ServerSocket socket = new ServerSocket(0)) {
				port = socket.getLocalPort();
			}
			Process process = new ProcessBuilder("docker", "run", "-d", "--rm", "-p", "127.0.0.1:" + port + ":8333",
					IMAGE, "mini", "-master.telemetry=false")
				.redirectErrorStream(true)
				.start();
			String[] output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim()
				.split("\\R");
			if (process.waitFor() != 0) {
				throw new IllegalStateException(String.join("\n", output));
			}
			return new SeaweedFs(output[output.length - 1], URI.create("http://127.0.0.1:" + port));
		}

		void awaitReady(S3Client client) throws Exception {
			long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
			while (true) {
				try {
					client.listBuckets();
					return;
				}
				catch (RuntimeException failure) {
					if (System.nanoTime() >= deadline) {
						throw failure;
					}
					Thread.sleep(100);
				}
			}
		}

		@Override
		public void close() throws Exception {
			new ProcessBuilder("docker", "rm", "-f", this.container).start().waitFor();
		}
	}

}
