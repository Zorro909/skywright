package de.zorro909.skywright.backend.runstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.zorro909.skywright.backend.acceptance.SeaweedFsFixture;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
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

	@TempDir
	Path temporary;

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
						Region.US_EAST_1, true, Map.of("chunkedEncoding", "disabled"), credentials, "project", "run",
						UUID.randomUUID(), 1);
				try (S3RunStoreObjectStore objects = new S3RunStoreObjectStore(target)) {
					RunStoreAccess access = new RunStoreAccess(protocol, objects);
					assertThatThrownBy(() -> objects.head("another/run/v1/artifacts/output"))
						.hasMessageContaining("RUN_STORE_WRONG_RUN");
					assertThat(access.listOutputs(RunStoreOutputKind.ARTIFACT, 10, null).outputs())
						.extracting(RunStoreOutput::name)
						.containsExactly("reports/final.txt");
					assertThat(access.resolveCheckpoint("skywright-checkpoint:v1:1:sha256:" + checkpointDigest).size())
						.isEqualTo(checkpoint.length);
					URI download = access.presignDownload(key, 60).url();
					HttpResponse<byte[]> response = HttpClient.newHttpClient()
						.send(HttpRequest.newBuilder(download).timeout(Duration.ofSeconds(5)).build(),
								HttpResponse.BodyHandlers.ofByteArray());
					assertThat(response.body()).isEqualTo(body);
					assertThat(objects.measurements()).isNotEmpty();
					qualifyBoundedReadHistory(objects, key, body);
					qualifyMetadataAndStreaming(writer, bucket, objects, access, protocol);

					writer.putObject(PutObjectRequest.builder()
						.bucket(bucket)
						.key(key)
						.contentType("application/octet-stream")
						.metadata(Map.of("skywright-sha256", sha256(body), "skywright-size",
								Integer.toString(body.length), "skywright-kind", "artifact", "skywright-schema", "v1"))
						.build(), AsyncRequestBody.fromBytes("corrupt".getBytes(StandardCharsets.UTF_8))).join();
					assertThatThrownBy(() -> access.listOutputs(RunStoreOutputKind.ARTIFACT, 10, null))
						.isInstanceOf(RunStoreIntegrityException.class)
						.hasMessageContaining("RUN_STORE_METADATA_MISMATCH");
				}
			}
		}
	}

	private void qualifyMetadataAndStreaming(S3AsyncClient writer, String bucket, S3RunStoreObjectStore objects,
			RunStoreAccess access, RunStoreProtocol protocol) throws Exception {
		byte[] body = new byte[4 * 1024 * 1024];
		String digest = sha256(body);
		String first = null;
		for (int step = 0; step < 100; step++) {
			String key = protocol.sampleKey("123e4567-e89b-12d3-a456-426614174000", step, "large.bin");
			if (first == null) {
				first = key;
			}
			writer
				.putObject(PutObjectRequest.builder()
					.bucket(bucket)
					.key(key)
					.contentType("application/octet-stream")
					.metadata(Map.of("skywright-schema", "v1", "skywright-kind", "sample", "skywright-size",
							Integer.toString(body.length), "skywright-sha256", digest))
					.build(), AsyncRequestBody.fromBytes(body))
				.join();
		}
		objects.drainMeasurements();
		System.gc();
		long initialHeap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
		long peakHeap = initialHeap;
		var counts = new java.util.HashMap<String, Long>();
		long transferred = 0;
		String continuation = null;
		int total = 0;
		do {
			RunStoreOutputPage page = access.listOutputs(RunStoreOutputKind.SAMPLE, 25, continuation);
			assertThat(page.outputs()).hasSize(25);
			for (RunStoreOutput output : page.outputs()) {
				RunStoreDownloadLink link = access.presignDownload(output.key(), 60);
				assertThat(link.size()).isEqualTo(body.length);
				assertThat(link.digest()).isEqualTo(digest);
				assertThat(link.verification()).isEqualTo(RunStoreDownloadLink.Verification.NOT_RECORDED);
			}
			total += page.outputs().size();
			continuation = page.continuation();
			var measurements = objects.drainMeasurements();
			assertThat(measurements.gap()).isNull();
			for (var item : measurements.measurements()) {
				counts.merge(item.operation(), 1L, Long::sum);
				transferred += item.bytes();
			}
			System.gc();
			peakHeap = Math.max(peakHeap, ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
		}
		while (continuation != null);
		assertThat(total).isEqualTo(100);
		assertThat(counts).containsExactlyInAnyOrderEntriesOf(
				Map.of("ListObjectsV2", 4L, "HeadObject", 200L, "PresignGetObject", 100L));
		assertThat(transferred).isZero();
		assertThat(peakHeap - initialHeap).isLessThan(32L * 1024 * 1024);
		try (VerifiedRunStoreObject accepted = access.stageDownload(first, temporary, body.length)) {
			assertThat(Files.size(accepted.path())).isEqualTo(body.length);
		}
		var read = objects.drainMeasurements();
		assertThat(read.measurements()).singleElement().satisfies(item -> {
			assertThat(item.operation()).isEqualTo("GetObject");
			assertThat(item.bytes()).isEqualTo(body.length);
			assertThat(item.succeeded()).isTrue();
		});
		// Same-size corruption cannot be detected by HEAD. It must fail consumption.
		body[0] = 1;
		writer
			.putObject(PutObjectRequest.builder()
				.bucket(bucket)
				.key(first)
				.contentType("application/octet-stream")
				.metadata(Map.of("skywright-schema", "v1", "skywright-kind", "sample", "skywright-size",
						Integer.toString(body.length), "skywright-sha256", digest))
				.build(), AsyncRequestBody.fromBytes(body))
			.join();
		String corruptedKey = first;
		assertThat(access.presignDownload(corruptedKey, 60).verification())
			.isEqualTo(RunStoreDownloadLink.Verification.NOT_RECORDED);
		assertThatThrownBy(() -> access.stageDownload(corruptedKey, temporary, body.length))
			.hasMessageContaining("RUN_STORE_DIGEST_MISMATCH");
		try (var files = Files.list(temporary)) {
			assertThat(files).isEmpty();
		}
		System.out
			.println("Run Store metadata evidence: outputs=" + total + ", object_bytes=" + body.length + ", calls="
					+ counts + ", listing_signing_payload_bytes=" + transferred + ", retained_heap_growth_bytes="
					+ (peakHeap - initialHeap) + ", verified_download_bytes=" + read.measurements().getFirst().bytes());
	}

	private static void qualifyBoundedReadHistory(S3RunStoreObjectStore objects, String key, byte[] expected) {
		for (int request = 0; request < 512; request++) {
			assertThat(objects.get(key).bytes()).isEqualTo(expected);
		}
		long initialSequence = objects.drainMeasurements().throughSequence();
		var memory = new ArrayList<Long>();
		var times = new ArrayList<Long>();
		for (int round = 0; round < 4; round++) {
			long started = System.nanoTime();
			for (int request = 0; request < 1024; request++) {
				assertThat(objects.get(key).bytes()).isEqualTo(expected);
			}
			times.add((System.nanoTime() - started) / 1_000_000);
			System.gc();
			memory.add(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
			assertThat(objects.measurements()).hasSize(256);
		}
		var batch = objects.drainMeasurements();
		assertThat(batch.throughSequence() - initialSequence).isEqualTo(4096);
		assertThat(batch.gap().count() + batch.measurements().size()).isEqualTo(4096);
		assertThat(batch.gap().firstSequence()).isEqualTo(initialSequence + 1);
		assertThat(batch.measurements()).allSatisfy(item -> {
			assertThat(item.operation()).isEqualTo("GetObject");
			assertThat(item.bytes()).isEqualTo(expected.length);
			assertThat(item.succeeded()).isTrue();
			assertThat(item.producerId()).isEqualTo(batch.producerId());
		});
		long growth = memory.stream().mapToLong(Long::longValue).max().orElseThrow() - memory.getFirst();
		assertThat(growth).as("heap growth after warm-up").isLessThan(32L * 1024 * 1024);
		System.out.println("Run Store history evidence: requests=4096, retained=256, missing=" + batch.gap().count()
				+ ", heap_bytes=" + memory + ", batch_ms=" + times);
	}

	private static String sha256(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}

}
