package de.zorro909.skywright.backend.orchestration;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class SkyPilotApiServerFixture implements AutoCloseable {

	private Process process;

	private final URI endpoint;

	private final Path environment;

	private final Path repository;

	private final Path logs;

	private final String listenAddress;

	private final Path home;

	private SkyPilotApiServerFixture(Process process, URI endpoint, Path environment, Path repository, Path logs,
			String listenAddress, Path home) {
		this.process = process;
		this.endpoint = endpoint;
		this.environment = environment;
		this.repository = repository;
		this.logs = logs;
		this.listenAddress = listenAddress;
		this.home = home;
	}

	static SkyPilotApiServerFixture start() throws Exception {
		return start("127.0.0.1");
	}

	/** Bind to a container-reachable interface while keeping server state isolated. */
	public static SkyPilotApiServerFixture start(String listenAddress) throws Exception {
		var repository = Path.of(System.getProperty("repository.root"));
		var environment = repository.resolve("backend/target/skypilot-api-server-venv");
		run(repository, "uv", "venv", "--python", "3.12", environment.toString());
		run(repository, "uv", "pip", "sync", "--python", environment.resolve("bin/python").toString(),
				repository.resolve("graalpy-environment/graalpy.lock").toString());

		var port = availablePort();
		var logs = repository.resolve("backend/target/service-logs/skypilot-api-" + port + ".log");
		Files.createDirectories(logs.getParent());
		var home = Files.createTempDirectory(repository.resolve("backend/target"), "skypilot-server-home-");
		var process = startProcess(environment, repository, logs, port, listenAddress, home);
		var fixture = new SkyPilotApiServerFixture(process, URI.create("http://127.0.0.1:" + port), environment,
				repository, logs, listenAddress, home);
		try {
			fixture.awaitHealthy(Duration.ofSeconds(30), logs);
		}
		catch (Exception | AssertionError failure) {
			fixture.close();
			throw failure;
		}
		return fixture;
	}

	public URI endpoint() {
		return this.endpoint;
	}

	void stop() throws Exception {
		var descendants = this.process.descendants().toList();
		descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
		this.process.destroyForcibly();
		this.process.waitFor(5, TimeUnit.SECONDS);
	}

	void restart() throws Exception {
		this.process = startProcess(this.environment, this.repository, this.logs, this.endpoint.getPort(),
				this.listenAddress, this.home);
		awaitHealthy(Duration.ofSeconds(30), this.logs);
	}

	private void awaitHealthy(Duration timeout, Path logs) throws Exception {
		var client = HttpClient.newHttpClient();
		var request = HttpRequest.newBuilder(this.endpoint.resolve("/api/health")).GET().build();
		var deadline = Instant.now().plus(timeout);
		while (Instant.now().isBefore(deadline)) {
			if (!this.process.isAlive()) {
				throw new AssertionError("SkyPilot API server exited during startup:\n" + Files.readString(logs));
			}
			try {
				if (client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
					return;
				}
			}
			catch (IOException ignored) {
				// The API server has not bound its socket yet.
			}
			Thread.sleep(100);
		}
		throw new AssertionError("SkyPilot API server did not become healthy:\n" + Files.readString(logs));
	}

	private static int availablePort() throws IOException {
		try (var socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	private static Process startProcess(Path environment, Path repository, Path logs, int port, String listenAddress,
			Path home) throws IOException {
		var builder = new ProcessBuilder(environment.resolve("bin/python").toString(), "-m", "sky.server.server",
				"--host", listenAddress, "--port", Integer.toString(port))
			.directory(repository.toFile())
			.redirectErrorStream(true)
			.redirectOutput(ProcessBuilder.Redirect.appendTo(logs.toFile()));
		builder.environment().put("HOME", home.toString());
		builder.environment().put("XDG_CACHE_HOME", home.resolve(".cache").toString());
		return builder.start();
	}

	private static void run(Path workingDirectory, String... command) throws Exception {
		var process = new ProcessBuilder(command).directory(workingDirectory.toFile()).inheritIO().start();
		if (!process.waitFor(2, TimeUnit.MINUTES) || process.exitValue() != 0) {
			throw new AssertionError("Test prerequisite failed: " + String.join(" ", command));
		}
	}

	@Override
	public void close() throws Exception {
		if (this.process.isAlive()) {
			stop();
		}
	}

}
