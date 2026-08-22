package de.zorro909.skywright.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class ProductionImageIT {

	private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(60);

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final HttpClient httpClient = HttpClient.newHttpClient();

	private String runningContainer;

	private String databaseContainer;

	private String network;

	private final String migrationPassword = UUID.randomUUID().toString();

	private final String runtimePassword = UUID.randomUUID().toString();

	private URI baseUri;

	@BeforeAll
	void startProductionImage() throws Exception {
		network = containerName("network");
		databaseContainer = containerName("database");
		docker("network", "create", network);
		docker("run", "--detach", "--name", databaseContainer, "--network", network, "--env", "POSTGRES_DB=skywright",
				"--env", "POSTGRES_USER=skywright_migrator", "--env", "POSTGRES_PASSWORD=" + migrationPassword,
				postgresqlImage());
		awaitPostgreSql(databaseContainer, STARTUP_TIMEOUT);
		docker("exec", databaseContainer, "psql", "--username", "skywright_migrator", "--dbname", "skywright", "--set",
				"ON_ERROR_STOP=1", "--command",
				"CREATE ROLE skywright_runtime LOGIN PASSWORD '" + runtimePassword
						+ "'; CREATE SCHEMA skywright AUTHORIZATION skywright_migrator;"
						+ " GRANT USAGE ON SCHEMA skywright TO skywright_runtime;");
		runningContainer = containerName("running");
		var arguments = applicationContainerArguments(runningContainer);
		arguments.addAll(List.of("--read-only", "--tmpfs", "/tmp:rw,exec,nosuid,size=128m", "--publish",
				"127.0.0.1::8080", imageName()));
		docker(arguments.toArray(String[]::new));
		var port = awaitPublishedPort(runningContainer, STARTUP_TIMEOUT);
		baseUri = URI.create("http://127.0.0.1:" + port);
		awaitStatus("/readyz", 200, STARTUP_TIMEOUT);
	}

	@AfterAll
	void removeProductionImage() {
		if (runningContainer != null) {
			dockerIgnoringFailure("rm", "--force", runningContainer);
		}
		if (databaseContainer != null) {
			dockerIgnoringFailure("rm", "--force", databaseContainer);
		}
		if (network != null) {
			dockerIgnoringFailure("network", "rm", network);
		}
	}

	@Test
	void productionProcessRunsAsANonRootUser() throws Exception {
		var processTable = docker("top", runningContainer, "-eo", "user,pid,comm");
		var applicationProcess = processTable.lines()
			.skip(1)
			.map(String::strip)
			.filter(line -> line.matches("\\S+\\s+\\d+\\s+java"))
			.findFirst();

		assertThat(applicationProcess).hasValueSatisfying(row -> assertThat(row).doesNotStartWith("root "));
	}

	@Test
	void healthAndApplicationIdentityAreAvailable() throws Exception {
		assertThat(get("/livez").body()).contains("\"status\":\"UP\"");
		assertThat(get("/readyz").body()).contains("\"status\":\"UP\"");
		assertThat(get("/actuator/health").body()).contains("\"status\":\"UP\"");

		var identity = JSON.readTree(get("/api/v1/system-information").body());
		assertThat(identity.path("applicationVersion").asText()).isEqualTo("0.1.0-SNAPSHOT");
		assertThat(identity.path("sourceRevision").asText()).isEqualTo(sourceRevision());
	}

	@Test
	void servedOpenApiIsTheCanonicalBuildInputByteForByte() throws Exception {
		var response = getBytes("/openapi/skywright-api.yaml");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).isEqualTo(Files.readAllBytes(Path.of(openApiDocument())));
	}

	@Test
	void requestsProduceStructuredSafeConsoleOutput() throws Exception {
		var correlationId = "image-acceptance-" + UUID.randomUUID();
		var request = HttpRequest.newBuilder(baseUri.resolve("/readyz?secret=hidden-value"))
			.header("X-Correlation-ID", correlationId)
			.GET()
			.build();
		assertThat(httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);

		var logs = awaitLogsContaining(runningContainer, correlationId, Duration.ofSeconds(10));
		assertThat(logs).doesNotContain("hidden-value");
		var events = logs.lines().filter(line -> !line.isBlank()).map(ProductionImageIT::readEvent).toList();
		assertThat(events).isNotEmpty().anySatisfy(event -> {
			assertThat(event.path("correlationId").asText()).isEqualTo(correlationId);
			assertThat(event.path("http").path("request").path("method").asText()).isEqualTo("GET");
			assertThat(event.path("http").path("route").asText()).isEqualTo("/readyz");
			assertThat(event.path("http").path("response").path("status_code").asInt()).isEqualTo(200);
			assertThat(event.path("event").path("duration").asLong()).isPositive();
		});
	}

	@Test
	void invalidConfigurationFailsWithoutDisclosingTheSuppliedValue() throws Exception {
		var container = containerName("invalid-configuration");
		var sensitiveValue = "production-private-token!";
		try {
			var arguments = applicationContainerArguments(container, sensitiveValue);
			arguments.set(0, "create");
			arguments.remove("--detach");
			arguments.add(imageName());
			docker(arguments.toArray(String[]::new));
			docker("start", container);
			var exitCode = docker("wait", container).strip();
			var logs = dockerCombinedOutput("logs", container);

			assertThat(exitCode).isNotEqualTo("0");
			assertThat(logs).contains("skywright.deployment.environment").doesNotContain(sensitiveValue);
		}
		finally {
			dockerIgnoringFailure("rm", "--force", container);
		}
	}

	@Test
	void productionProcessTerminatesWithinTheDocumentedStopWindow() throws Exception {
		var container = containerName("termination");
		try (var skyPilot = HeldSkyPilotServer.start()) {
			var arguments = applicationContainerArguments(container);
			arguments.addAll(
					List.of("--read-only", "--tmpfs", "/tmp:rw,exec,nosuid,size=128m", "--add-host",
							"skywright-test-host:host-gateway", "--env",
							"SKYWRIGHT_SKYPILOT_BRIDGE_API_SERVER_ENDPOINT=http://skywright-test-host:"
									+ skyPilot.port(),
							"--env", "NO_PROXY=skywright-test-host,127.0.0.1,localhost", "--env",
							"no_proxy=skywright-test-host,127.0.0.1,localhost", "--env",
							"SKYWRIGHT_SKYPILOT_BRIDGE_AVAILABILITY_PROBE_INTERVAL=100ms", imageName()));
			docker(arguments.toArray(String[]::new));
			awaitLogsContaining(container, "SkyPilot capability available", STARTUP_TIMEOUT);
			skyPilot.holdRequests();
			skyPilot.awaitHeld(Duration.ofSeconds(10));
			skyPilot.releaseAfter(Duration.ofSeconds(10));

			var started = Instant.now();
			docker("stop", "--time", "30", container);
			var elapsed = Duration.between(started, Instant.now());
			var logs = docker("logs", container);

			assertThat(docker("inspect", "--format", "{{.State.OOMKilled}}", container).strip()).isEqualTo("false");
			assertThat(docker("inspect", "--format", "{{.State.ExitCode}}", container).strip()).isIn("0", "143");
			assertThat(logs).contains("SkyPilot capability available", "REFUSING_TRAFFIC", "Graceful shutdown complete")
				.doesNotContain("SIGSEGV", "A fatal error has been detected", "hs_err_pid");
			assertThat(elapsed).isLessThan(Duration.ofSeconds(30));
		}
		finally {
			dockerIgnoringFailure("rm", "--force", container);
		}
	}

	private HttpResponse<String> get(String path) throws IOException, InterruptedException {
		var response = httpClient.send(HttpRequest.newBuilder(baseUri.resolve(path)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(200);
		return response;
	}

	private HttpResponse<byte[]> getBytes(String path) throws IOException, InterruptedException {
		return httpClient.send(HttpRequest.newBuilder(baseUri.resolve(path)).GET().build(),
				HttpResponse.BodyHandlers.ofByteArray());
	}

	private void awaitStatus(String path, int expectedStatus, Duration timeout) throws Exception {
		var deadline = Instant.now().plus(timeout);
		while (Instant.now().isBefore(deadline)) {
			try {
				if (getBytes(path).statusCode() == expectedStatus) {
					return;
				}
			}
			catch (IOException ignored) {
				// The container is still starting.
			}
			Thread.sleep(Duration.ofMillis(100));
		}
		throw new AssertionError("Container did not serve " + path + " within " + timeout + ":\n"
				+ dockerCombinedOutput("logs", runningContainer));
	}

	private static int awaitPublishedPort(String container, Duration timeout) throws Exception {
		var deadline = Instant.now().plus(timeout);
		while (Instant.now().isBefore(deadline)) {
			var published = docker("port", container, "8080/tcp").strip();
			if (!published.isBlank()) {
				return Integer.parseInt(published.substring(published.lastIndexOf(':') + 1));
			}
			Thread.sleep(Duration.ofMillis(100));
		}
		throw new AssertionError("Container runtime did not publish the application port");
	}

	private static void awaitPostgreSql(String container, Duration timeout) throws Exception {
		var deadline = Instant.now().plus(timeout);
		Instant continuouslyReadySince = null;
		while (Instant.now().isBefore(deadline)) {
			try {
				docker("exec", container, "pg_isready", "--username", "skywright_migrator", "--dbname", "skywright");
				if (continuouslyReadySince == null) {
					continuouslyReadySince = Instant.now();
				}
				else if (Duration.between(continuouslyReadySince, Instant.now())
					.compareTo(Duration.ofSeconds(1)) >= 0) {
					return;
				}
			}
			catch (AssertionError ignored) {
				// PostgreSQL is still starting.
				continuouslyReadySince = null;
			}
			Thread.sleep(Duration.ofMillis(100));
		}
		throw new AssertionError(
				"PostgreSQL did not become ready within " + timeout + ":\n" + dockerCombinedOutput("logs", container));
	}

	private ArrayList<String> applicationContainerArguments(String container) {
		return applicationContainerArguments(container, "production");
	}

	private ArrayList<String> applicationContainerArguments(String container, String deploymentEnvironment) {
		var databaseUrl = "jdbc:postgresql://" + databaseContainer
				+ ":5432/skywright?connectTimeout=5&socketTimeout=5&tcpKeepAlive=true";
		return new ArrayList<>(List.of("run", "--detach", "--name", container, "--network", network, "--env",
				"SKYWRIGHT_DEPLOYMENT_ENVIRONMENT=" + deploymentEnvironment, "--env",
				"SKYWRIGHT_DATABASE_MIGRATION_URL=" + databaseUrl, "--env",
				"SKYWRIGHT_DATABASE_MIGRATION_USERNAME=skywright_migrator", "--env",
				"SKYWRIGHT_DATABASE_MIGRATION_PASSWORD=" + migrationPassword, "--env",
				"SKYWRIGHT_DATABASE_RUNTIME_URL=" + databaseUrl, "--env",
				"SKYWRIGHT_DATABASE_RUNTIME_USERNAME=skywright_runtime", "--env",
				"SKYWRIGHT_DATABASE_RUNTIME_PASSWORD=" + runtimePassword));
	}

	private static String awaitLogsContaining(String container, String expected, Duration timeout) throws Exception {
		var deadline = Instant.now().plus(timeout);
		while (Instant.now().isBefore(deadline)) {
			var logs = docker("logs", container);
			if (logs.contains(expected)) {
				return logs;
			}
			Thread.sleep(Duration.ofMillis(100));
		}
		throw new AssertionError(
				"Container logs did not contain " + expected + ":\n" + dockerCombinedOutput("logs", container));
	}

	private static JsonNode readEvent(String line) {
		try {
			return JSON.readTree(line);
		}
		catch (RuntimeException exception) {
			throw new AssertionError("Container output was not structured JSON: " + line, exception);
		}
	}

	private static String docker(String... arguments) throws Exception {
		return docker(false, arguments);
	}

	private static String dockerCombinedOutput(String... arguments) throws Exception {
		return docker(true, arguments);
	}

	private static String docker(boolean combineOutput, String... arguments) throws Exception {
		var command = new ArrayList<String>();
		command.add("docker");
		command.addAll(Arrays.asList(arguments));
		var process = new ProcessBuilder(command).redirectErrorStream(combineOutput).start();
		var standardOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		var errorOutput = combineOutput ? ""
				: new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
		var exitCode = process.waitFor();
		if (exitCode != 0) {
			var failureOutput = combineOutput ? standardOutput : errorOutput;
			throw new AssertionError(String.join(" ", command) + " exited " + exitCode + ":\n" + failureOutput);
		}
		return standardOutput;
	}

	private static void dockerIgnoringFailure(String... arguments) {
		try {
			docker(arguments);
		}
		catch (Exception | AssertionError ignored) {
			// Best-effort cleanup preserves the original test failure.
		}
	}

	private static String containerName(String purpose) {
		return "skywright-" + purpose + "-" + UUID.randomUUID().toString().substring(0, 8);
	}

	private static String imageName() {
		return System.getProperty("image.name");
	}

	private static String postgresqlImage() {
		return System.getProperty("postgresql.container.image");
	}

	private static String openApiDocument() {
		return System.getProperty("openapi.document");
	}

	private static String sourceRevision() {
		return System.getProperty("source.revision");
	}

	private static final class HeldSkyPilotServer implements AutoCloseable {

		private static final byte[] HEALTH = ("{\"status\":\"healthy\",\"api_version\":\"56\","
				+ "\"version\":\"0.13.0\",\"version_on_disk\":\"0.13.0\",\"commit\":\"test\","
				+ "\"basic_auth_enabled\":false,\"user\":null}")
			.getBytes(StandardCharsets.UTF_8);

		private final HttpServer server;

		private final java.util.concurrent.ScheduledExecutorService executor;

		private final AtomicBoolean holding = new AtomicBoolean();

		private final CountDownLatch held = new CountDownLatch(1);

		private final CountDownLatch release = new CountDownLatch(1);

		private HeldSkyPilotServer(HttpServer server, java.util.concurrent.ScheduledExecutorService executor) {
			this.server = server;
			this.executor = executor;
		}

		static HeldSkyPilotServer start() throws IOException {
			var server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
			var executor = Executors.newScheduledThreadPool(2,
					Thread.ofPlatform().daemon().name("skypilot-test-", 0).factory());
			var fixture = new HeldSkyPilotServer(server, executor);
			server.createContext("/api/health", exchange -> fixture.respond(exchange));
			server.setExecutor(executor);
			server.start();
			return fixture;
		}

		int port() {
			return this.server.getAddress().getPort();
		}

		void holdRequests() {
			this.holding.set(true);
		}

		void awaitHeld(Duration timeout) throws InterruptedException {
			assertThat(this.held.await(timeout.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
		}

		void releaseAfter(Duration delay) {
			this.executor.schedule(this.release::countDown, delay.toMillis(), TimeUnit.MILLISECONDS);
		}

		private void respond(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
			if (this.holding.get()) {
				this.held.countDown();
				try {
					this.release.await();
				}
				catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
				}
			}
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.getResponseHeaders().set("X-SkyPilot-API-Version", "56");
			exchange.sendResponseHeaders(200, HEALTH.length);
			try (var body = exchange.getResponseBody()) {
				body.write(HEALTH);
			}
		}

		@Override
		public void close() {
			this.release.countDown();
			this.server.stop(0);
			this.executor.shutdownNow();
		}

	}

}
