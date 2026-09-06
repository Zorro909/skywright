package de.zorro909.skywright.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import de.zorro909.skywright.backend.acceptance.SeaweedFsFixture;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class DatasetPublicationImageIT {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final long SHARD_BYTES = 256L * 1024 * 1024;

	private static final String FORMAT = "mosaicml-streaming-mds@2";

	private final List<String> containers = new ArrayList<>();

	private SeaweedFsFixture storage;

	private S3AsyncClient client;

	private Path directory;

	private Path shard;

	private String bucket;

	private String digest;

	private String temporaryMount;

	@BeforeAll
	void prepare() throws Exception {
		this.temporaryMount = BackendPodBudget.temporaryMount();
		this.directory = Files.createTempDirectory("skywright-publication-image-");
		Files.setPosixFilePermissions(this.directory, PosixFilePermissions.fromString("rwxr-xr-x"));
		this.shard = this.directory.resolve("source.mds");
		MessageDigest checksum = MessageDigest.getInstance("SHA-256");
		byte[] chunk = new byte[1024 * 1024];
		java.util.Arrays.fill(chunk, (byte) 'x');
		try (var output = Files.newOutputStream(this.shard)) {
			for (int index = 0; index < 256; index++) {
				output.write(chunk);
				checksum.update(chunk);
			}
		}
		this.digest = "sha256:" + HexFormat.of().formatHex(checksum.digest());
		this.storage = SeaweedFsFixture.start();
		this.client = S3AsyncClient.builder()
			.httpClientBuilder(NettyNioAsyncHttpClient.builder())
			.endpointOverride(this.storage.endpoint())
			.region(Region.US_EAST_1)
			.credentialsProvider(
					StaticCredentialsProvider.create(AwsBasicCredentials.create("test-key", "test-secret")))
			.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
			.serviceConfiguration(
					S3Configuration.builder().pathStyleAccessEnabled(true).chunkedEncodingEnabled(false).build())
			.build();
		this.storage.awaitReady(this.client);
		this.bucket = "publication-image-" + UUID.randomUUID();
		this.client.createBucket(CreateBucketRequest.builder().bucket(this.bucket).build()).join();
		for (int index = 0; index < 4; index++) {
			this.client
				.putObject(PutObjectRequest.builder().bucket(this.bucket).key("payload/" + index + ".mds").build(),
						AsyncRequestBody.fromFile(this.shard))
				.join();
		}
		byte[] manifest = manifest(4);
		this.client
			.putObject(PutObjectRequest.builder().bucket(this.bucket).key("operation/manifest.json").build(),
					AsyncRequestBody.fromBytes(manifest))
			.join();
		writeJob("job.json", this.storage.endpoint(), manifest, 4);
	}

	@AfterAll
	void release() throws Exception {
		for (String container : this.containers) {
			command(false, "docker", "rm", "--force", container);
		}
		if (this.client != null) {
			this.client.close();
		}
		if (this.storage != null) {
			this.storage.close();
		}
		if (this.directory != null) {
			try (var paths = Files.walk(this.directory)) {
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
					Files.deleteIfExists(path);
				}
			}
		}
	}

	@Test
	void verifiesFourConcurrent256MiBShardsWithinThePackagedTemporaryBudget() throws Exception {
		String container = container();
		Process worker = worker(container, "job.json");
		assertThat(worker.waitFor(60, TimeUnit.SECONDS)).isTrue();
		assertThat(worker.exitValue()).isZero();
		JsonNode result = result(container);
		assertThat(result.path("verified").asBoolean()).isTrue();
		assertThat(result.path("objectCount").asLong()).isEqualTo(4);
		assertThat(result.path("byteCount").asLong()).isEqualTo(4 * SHARD_BYTES);
		assertThat(result.path("manifest").size()).isEqualTo(4);
		assertThat(command(true, "docker", "exec", container, "find", "/tmp", "-name", "skywright-dataset-worker-*"))
			.isBlank();
		System.out.println("Dataset publication image evidence: shards=4, shard_bytes=" + SHARD_BYTES
				+ ", temporary_mount=" + this.temporaryMount + ", verified=true");
	}

	@Test
	void rejectsSameSizeCorruptionWithoutReturningAVerifiedManifest() throws Exception {
		try {
			try (var file = new java.io.RandomAccessFile(this.shard.toFile(), "rw")) {
				file.write('y');
			}
			this.client
				.putObject(PutObjectRequest.builder().bucket(this.bucket).key("payload/0.mds").build(),
						AsyncRequestBody.fromFile(this.shard))
				.join();
			String container = container();
			Process worker = worker(container, "job.json");
			assertThat(worker.waitFor(60, TimeUnit.SECONDS)).isTrue();
			assertThat(worker.exitValue()).isZero();
			JsonNode result = result(container);
			assertThat(result.path("verified").asBoolean()).isFalse();
			assertThat(result.path("failureCode").asText()).isEqualTo("DATASET_REMOTE_MANIFEST_MISMATCH");
			assertThat(result.path("manifest").size()).isZero();
		}
		finally {
			try (var file = new java.io.RandomAccessFile(this.shard.toFile(), "rw")) {
				file.write('x');
			}
			this.client
				.putObject(PutObjectRequest.builder().bucket(this.bucket).key("payload/0.mds").build(),
						AsyncRequestBody.fromFile(this.shard))
				.join();
		}
	}

	@Test
	void reportsExhaustedControlStorageAndCanRetryAfterItIsReleased() throws Exception {
		String container = container();
		command(true, "docker", "exec", container, "sh", "-c",
				"dd if=/dev/zero of=/tmp/exhausted bs=1M count=64 2>/dev/null");
		Process worker = worker(container, "job.json");
		assertThat(worker.waitFor(60, TimeUnit.SECONDS)).isTrue();
		assertThat(worker.exitValue()).isEqualTo(74);
		assertThat(Files.readString(this.directory.resolve("worker-" + container + ".log")))
			.contains("DATASET_WORKER_TEMPORARY_STORAGE_UNAVAILABLE");
		assertThat(command(false, "docker", "exec", container, "sh", "-c",
				"test ! -e /tmp/result.json && echo unpublished")
			.strip()).isEqualTo("unpublished");
		command(true, "docker", "exec", container, "rm", "-f", "/tmp/exhausted", "/tmp/result.json.pending");
		Process retry = worker(container, "job.json");
		assertThat(retry.waitFor(60, TimeUnit.SECONDS)).isTrue();
		assertThat(retry.exitValue()).isZero();
		assertThat(result(container).path("verified").asBoolean()).isTrue();
	}

	@Test
	void interruptedShardReadNeverPublishesAResultAndRestartsWithoutPayloadStaging() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicBoolean first = new AtomicBoolean(true);
		byte[] manifest = manifest(1);
		HttpServer controlled = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		var threads = Executors.newCachedThreadPool();
		controlled.setExecutor(threads);
		controlled.createContext("/", exchange -> {
			try {
				String path = exchange.getRequestURI().getPath();
				if (path.endsWith("manifest.json")) {
					exchange.sendResponseHeaders(200, manifest.length);
					exchange.getResponseBody().write(manifest);
				}
				else if (path.endsWith(".mds")) {
					exchange.sendResponseHeaders(200, SHARD_BYTES);
					if (first.compareAndSet(true, false)) {
						exchange.getResponseBody().write(new byte[1024]);
						exchange.getResponseBody().flush();
						entered.countDown();
						release.await(30, TimeUnit.SECONDS);
					}
					else {
						Files.copy(this.shard, exchange.getResponseBody());
					}
				}
				else {
					byte[] listing = ("<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><IsTruncated>false</IsTruncated><Contents><Key>payload/0.mds</Key><Size>"
							+ SHARD_BYTES + "</Size></Contents></ListBucketResult>")
						.getBytes(StandardCharsets.UTF_8);
					exchange.sendResponseHeaders(200, listing.length);
					exchange.getResponseBody().write(listing);
				}
			}
			catch (InterruptedException failure) {
				Thread.currentThread().interrupt();
			}
			catch (IOException expectedAfterCancellation) {
				/* The killed consumer closes its socket. */ }
			finally {
				exchange.close();
			}
		});
		controlled.start();
		try {
			writeJob("held.json", URI.create("http://127.0.0.1:" + controlled.getAddress().getPort()), manifest, 1);
			String container = container();
			Process interrupted = worker(container, "held.json");
			assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();
			command(true, "docker", "stop", "--time", "1", container);
			assertThat(interrupted.waitFor(10, TimeUnit.SECONDS)).isTrue();
			assertThat(interrupted.exitValue()).isNotZero();
			release.countDown();
			command(true, "docker", "start", container);
			assertThat(command(true, "docker", "exec", container, "sh", "-c",
					"test ! -e /tmp/result.json && echo unpublished")
				.strip()).isEqualTo("unpublished");
			Process restarted = worker(container, "held.json");
			assertThat(restarted.waitFor(60, TimeUnit.SECONDS)).isTrue();
			assertThat(restarted.exitValue()).isZero();
			assertThat(result(container).path("verified").asBoolean()).isTrue();
		}
		finally {
			release.countDown();
			controlled.stop(0);
			threads.shutdownNow();
		}
	}

	private byte[] manifest(int count) {
		var objects = new ArrayList<Map<String, Object>>();
		for (int index = 0; index < count; index++) {
			objects.add(Map.of("objectKey", index + ".mds", "byteCount", SHARD_BYTES, "sha256", this.digest));
		}
		return JSON.writeValueAsBytes(Map.of("version", "skywright-dataset-manifest@1", "format", FORMAT, "objectCount",
				count, "byteCount", count * SHARD_BYTES, "objects", objects));
	}

	private void writeJob(String name, URI endpoint, byte[] manifest, int count) throws Exception {
		String identity = digest(manifest);
		String fingerprint = digest(("{\"format\":\"" + FORMAT + "\",\"manifest\":\"" + identity
				+ "\",\"version\":\"skywright-dataset-content@1\"}")
			.getBytes(StandardCharsets.UTF_8));
		var job = new LinkedHashMap<String, Object>();
		job.put("action", "VERIFY");
		job.put("endpoint", endpoint.toString());
		job.put("bucket", this.bucket);
		job.put("region", "us-east-1");
		job.put("pathStyleAccess", true);
		job.put("chunkedEncoding", false);
		job.put("formatIdentity", FORMAT);
		job.put("manifestIdentity", identity);
		job.put("contentFingerprint", fingerprint);
		job.put("objectCount", count);
		job.put("byteCount", count * SHARD_BYTES);
		job.put("payloadLocation", "payload");
		job.put("operationLocation", "operation");
		job.put("verificationConcurrency", 4);
		JSON.writeValue(this.directory.resolve(name).toFile(), job);
	}

	private String container() throws Exception {
		String name = "skywright-publication-" + UUID.randomUUID();
		this.containers.add(name);
		command(true, "docker", "run", "--detach", "--name", name, "--network", "host", "--read-only", "--tmpfs",
				this.temporaryMount, "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--user",
				"10001:10001", "--volume", this.directory + ":/input:ro,Z", "--entrypoint", "sleep",
				System.getProperty("image.name"), "infinity");
		return name;
	}

	private Process worker(String container, String job) throws Exception {
		Process process = new ProcessBuilder("docker", "exec", "-i", container, "java",
				"-Dloader.main=de.zorro909.skywright.backend.datasetpublication.DatasetPublicationWorkerMain", "-cp",
				"/opt/skywright/application.jar", "org.springframework.boot.loader.launch.PropertiesLauncher",
				"/input/" + job, "/tmp/result.json")
			.redirectErrorStream(true)
			.redirectOutput(this.directory.resolve("worker-" + container + ".log").toFile())
			.start();
		try (var credential = process.getOutputStream()) {
			credential.write("{\"accessKeyId\":\"test-key\",\"secretAccessKey\":\"test-secret\",\"sessionToken\":null}"
				.getBytes(StandardCharsets.UTF_8));
		}
		return process;
	}

	private JsonNode result(String container) throws Exception {
		return JSON.readTree(command(true, "docker", "exec", container, "cat", "/tmp/result.json"));
	}

	private static String digest(byte[] bytes) throws Exception {
		return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}

	private static String command(boolean requireSuccess, String... arguments) throws Exception {
		Path outputFile = Files.createTempFile("skywright-container-command-", ".out");
		Path errorFile = Files.createTempFile("skywright-container-command-", ".err");
		try {
			Process command = new ProcessBuilder(arguments).redirectOutput(outputFile.toFile())
				.redirectError(errorFile.toFile())
				.start();
			if (!command.waitFor(60, TimeUnit.SECONDS)) {
				command.destroyForcibly();
				throw new IllegalStateException("Container command timed out");
			}
			String output = Files.readString(outputFile);
			if (requireSuccess && command.exitValue() != 0) {
				throw new IllegalStateException(Files.readString(errorFile) + output);
			}
			return output;
		}
		finally {
			Files.deleteIfExists(outputFile);
			Files.deleteIfExists(errorFile);
		}
	}

}
