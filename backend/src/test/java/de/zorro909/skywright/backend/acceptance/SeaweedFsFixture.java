package de.zorro909.skywright.backend.acceptance;

import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import software.amazon.awssdk.services.s3.S3AsyncClient;

/** Shared pinned real-service fixture for every S3 compatibility scenario. */
public record SeaweedFsFixture(String container, URI endpoint, Path credentialConfiguration) implements AutoCloseable {

	private static final String IMAGE = "docker.io/chrislusf/seaweedfs:4.42@sha256:"
			+ "f7cbc8bdbbf60a1aaba7d61784a3bdff3ec1e0657f6ad0b26d5b6ab2cd9d0dc6";

	public static final String WORKSTATION_ACCESS_KEY = "workstation-key";

	public static final String WORKSTATION_SECRET_KEY = "workstation-secret";

	public static final String WORKER_ACCESS_KEY = "transfer-worker-key";

	public static final String WORKER_SECRET_KEY = "transfer-worker-secret";

	public static SeaweedFsFixture start() throws Exception {
		int port;
		try (ServerSocket socket = new ServerSocket(0)) {
			port = socket.getLocalPort();
		}
		Path configuration = Files.createTempFile("skywright-seaweedfs-s3-", ".json");
		Files.writeString(configuration,
				"""
						{"identities":[
						  {"name":"fixture-administrator","credentials":[{"accessKey":"test-key","secretKey":"test-secret"}],"actions":["Admin","Read","List","Tagging","Write"]},
						  {"name":"workstation-uploader","credentials":[{"accessKey":"%s","secretKey":"%s"}],"actions":["Read","List","Write"]},
						  {"name":"managed-transfer-worker","credentials":[{"accessKey":"%s","secretKey":"%s"}],"actions":["Read","List","Write"]}
						]}
						"""
					.formatted(WORKSTATION_ACCESS_KEY, WORKSTATION_SECRET_KEY, WORKER_ACCESS_KEY, WORKER_SECRET_KEY));
		Files.setPosixFilePermissions(configuration, PosixFilePermissions.fromString("rw-r--r--"));
		Process process = new ProcessBuilder("docker", "run", "-d", "-p", "127.0.0.1:" + port + ":8333", "-v",
				configuration.toAbsolutePath() + ":/etc/seaweedfs/s3.json:ro,Z", IMAGE, "mini",
				"-s3.config=/etc/seaweedfs/s3.json", "-master.telemetry=false")
			.redirectErrorStream(true)
			.start();
		String[] output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim()
			.split("\\R");
		if (process.waitFor() != 0) {
			Files.deleteIfExists(configuration);
			throw new IllegalStateException(String.join("\n", output));
		}
		return new SeaweedFsFixture(output[output.length - 1], URI.create("http://127.0.0.1:" + port), configuration);
	}

	public void awaitReady(S3AsyncClient client) throws Exception {
		long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
		while (true) {
			try {
				client.listBuckets().join();
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

	public void pause() throws Exception {
		if (new ProcessBuilder("docker", "pause", this.container).start().waitFor() != 0) {
			throw new IllegalStateException("Could not pause SeaweedFS");
		}
	}

	public void unpause() throws Exception {
		if (new ProcessBuilder("docker", "unpause", this.container).start().waitFor() != 0) {
			throw new IllegalStateException("Could not unpause SeaweedFS");
		}
	}

	@Override
	public void close() throws Exception {
		try {
			Process logs = new ProcessBuilder("docker", "logs", this.container).redirectErrorStream(true).start();
			byte[] output = logs.getInputStream().readAllBytes();
			logs.waitFor();
			Path directory = Path.of("target/service-logs");
			Files.createDirectories(directory);
			Files.write(directory.resolve("seaweedfs-" + this.container.substring(0, 12) + ".log"), output);
		}
		finally {
			new ProcessBuilder("docker", "rm", "-f", this.container).start().waitFor();
			Files.deleteIfExists(this.credentialConfiguration);
		}
	}

}
