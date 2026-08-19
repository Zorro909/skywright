package de.zorro909.skywright.backend.acceptance;

import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import software.amazon.awssdk.services.s3.S3AsyncClient;

/** Shared pinned real-service fixture for every S3 compatibility scenario. */
public record SeaweedFsFixture(String container, URI endpoint) implements AutoCloseable {

	private static final String IMAGE = "docker.io/chrislusf/seaweedfs:4.42@sha256:"
			+ "f7cbc8bdbbf60a1aaba7d61784a3bdff3ec1e0657f6ad0b26d5b6ab2cd9d0dc6";

	public static SeaweedFsFixture start() throws Exception {
		int port;
		try (ServerSocket socket = new ServerSocket(0)) {
			port = socket.getLocalPort();
		}
		Process process = new ProcessBuilder("docker", "run", "-d", "--rm", "-p", "127.0.0.1:" + port + ":8333", IMAGE,
				"mini", "-master.telemetry=false")
			.redirectErrorStream(true)
			.start();
		String[] output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim()
			.split("\\R");
		if (process.waitFor() != 0) {
			throw new IllegalStateException(String.join("\n", output));
		}
		return new SeaweedFsFixture(output[output.length - 1], URI.create("http://127.0.0.1:" + port));
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
		}
	}

}
