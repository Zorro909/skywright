package de.zorro909.skywright.backend.runstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.zorro909.skywright.backend.acceptance.SeaweedFsFixture;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Tag("real-service")
class RunStoreS3IT {

	@Test
	void javaAccessUsesTheSamePinnedSeaweedFsProtocol() throws Exception {
		try (SeaweedFsFixture service = SeaweedFsFixture.start()) {
			var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test-key", "test-secret"));
			String bucket = "skywright-" + UUID.randomUUID().toString();
			try (S3AsyncClient writer = S3AsyncClient.builder()
				.httpClientBuilder(NettyNioAsyncHttpClient.builder())
				.endpointOverride(service.endpoint())
				.region(Region.US_EAST_1)
				.credentialsProvider(credentials)
				.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
				.serviceConfiguration(
						S3Configuration.builder().pathStyleAccessEnabled(true).chunkedEncodingEnabled(false).build())
				.build()) {
				service.awaitReady(writer);
				writer.createBucket(CreateBucketRequest.builder().bucket(bucket).build()).join();
				RunStoreProtocol protocol = new RunStoreProtocol("project", "run");
				String key = protocol.artifactKey("123e4567-e89b-12d3-a456-426614174000", 1, "reports/final.txt");
				byte[] body = "finished".getBytes(StandardCharsets.UTF_8);
				writer
					.putObject(PutObjectRequest.builder()
						.bucket(bucket)
						.key(key)
						.contentType("application/octet-stream")
						.metadata(Map.of("skywright-sha256", sha256(body), "skywright-size",
								Integer.toString(body.length), "skywright-kind", "artifact", "skywright-schema", "v1",
								"skywright-media-type", "application/octet-stream"))
						.build(), AsyncRequestBody.fromBytes(body))
					.join();
				byte[] checkpoint = "checkpoint".getBytes(StandardCharsets.UTF_8);
				String checkpointDigest = sha256(checkpoint);
				String checkpointKey = protocol.checkpointKey(1, checkpointDigest);
				writer
					.putObject(PutObjectRequest.builder()
						.bucket(bucket)
						.key(checkpointKey)
						.contentType("application/octet-stream")
						.metadata(Map.of("skywright-sha256", checkpointDigest, "skywright-size",
								Integer.toString(checkpoint.length), "skywright-kind", "checkpoint", "skywright-schema",
								"v1"))
						.build(), AsyncRequestBody.fromBytes(checkpoint))
					.join();

				ResolvedTargetStorage target = new ResolvedTargetStorage("seaweedfs", service.endpoint(), bucket,
						Region.US_EAST_1, true, Map.of("chunkedEncoding", "disabled"), credentials, "project", "run");
				try (S3RunStoreObjectStore objects = new S3RunStoreObjectStore(target)) {
					RunStoreAccess access = new RunStoreAccess(protocol, objects);
					assertThat(access.listOutputs()).extracting(RunStoreOutput::name)
						.containsExactly("reports/final.txt");
					assertThat(access.resolveCheckpoint("skywright-checkpoint:v1:1:sha256:" + checkpointDigest).bytes())
						.containsExactly(checkpoint);
					URI download = access.presignDownload(key, 60);
					HttpResponse<byte[]> response = HttpClient.newHttpClient()
						.send(HttpRequest.newBuilder(download).timeout(Duration.ofSeconds(5)).build(),
								HttpResponse.BodyHandlers.ofByteArray());
					assertThat(response.body()).isEqualTo(body);
					assertThat(objects.measurements()).isNotEmpty();

					writer.putObject(PutObjectRequest.builder()
						.bucket(bucket)
						.key(key)
						.contentType("application/octet-stream")
						.metadata(Map.of("skywright-sha256", sha256(body), "skywright-size",
								Integer.toString(body.length), "skywright-kind", "artifact", "skywright-schema", "v1"))
						.build(), AsyncRequestBody.fromBytes("corrupt".getBytes(StandardCharsets.UTF_8))).join();
					assertThatThrownBy(access::listOutputs).isInstanceOf(RunStoreIntegrityException.class)
						.hasMessageContaining("RUN_STORE_DIGEST_MISMATCH");
				}
			}
		}
	}

	private static String sha256(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}

}
