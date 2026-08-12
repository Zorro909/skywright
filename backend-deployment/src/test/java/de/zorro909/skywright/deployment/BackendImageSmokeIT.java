package de.zorro909.skywright.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Smoke-verifies the Maven-built production OCI artifact through runtime and HTTP
 * boundaries.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class BackendImageSmokeIT {

	private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(2);

	private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(30);

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final String runtime = System.getProperty("backend.container.runtime");

	private final String image = System.getProperty("backend.container.image");

	private final String container = "skywright-backend-smoke-" + UUID.randomUUID().toString().substring(0, 8);

	private final Path moduleDirectory = Path.of(System.getProperty("backend.moduleDirectory"));

	private String runningContainer;

	private Process attachedContainer;

	private final List<String> standardOutput = Collections.synchronizedList(new ArrayList<>());

	private final List<String> standardError = Collections.synchronizedList(new ArrayList<>());

	private Thread standardOutputReader;

	private Thread standardErrorReader;

	@AfterAll
	void removeSmokeArtifacts() throws Exception {
		if (runningContainer != null) {
			command(Duration.ofSeconds(20), "rm", "--force", runningContainer);
		}
	}

	@Test
	void productionImageRunsThroughItsOperatorBoundary() throws Exception {
		assertImageConfiguration();
		var port = availablePort();
		startAttachedContainer("--read-only", "--tmpfs", "/tmp:rw,noexec,nosuid,size=64m", "--env",
				"SKYWRIGHT_DEPLOYMENT_ENVIRONMENT=acceptance", "--env", "JAVA_TOOL_OPTIONS=-Xss2m", "--env",
				"SERVER_TOMCAT_THREADS_MAX=1", "--env", "SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE=5s", "--publish",
				"127.0.0.1:" + port + ":8080");
		runningContainer = container;
		awaitReadiness(port, STARTUP_TIMEOUT);

		assertRuntimeIdentity();
		assertHttpBoundary(port);
		assertStructuredRequestLog(port);
		assertGracefulTermination(port);
	}

	@Test
	void invalidExternalConfigurationStopsTheContainerWithASanitizedDiagnostic() throws Exception {
		var sensitiveValue = "production-private-container-token!";
		var invalidContainer = container + "-invalid";
		var port = availablePort();
		var start = command(COMMAND_TIMEOUT, "run", "--detach", "--name", invalidContainer, "--read-only", "--tmpfs",
				"/tmp:rw,noexec,nosuid,size=64m", "--env", "SKYWRIGHT_DEPLOYMENT_ENVIRONMENT=" + sensitiveValue,
				"--publish", "127.0.0.1:" + port + ":8080", image);
		assertThat(start.exitCode()).as(start.output()).isZero();

		var becameReady = false;
		var deadline = Instant.now().plus(STARTUP_TIMEOUT);
		while (Instant.now().isBefore(deadline)
				&& Boolean.parseBoolean(success("inspect", "--format", "{{.State.Running}}", invalidContainer))) {
			try {
				becameReady |= get(port, "/readyz").statusCode() == 200;
			}
			catch (IOException ignored) {
				// Startup has not opened the application port.
			}
			Thread.sleep(Duration.ofMillis(10));
		}

		assertThat(becameReady).isFalse();
		assertThat(Integer.parseInt(success("inspect", "--format", "{{.State.ExitCode}}", invalidContainer)))
			.isNotZero();
		var output = success("logs", invalidContainer);
		assertThat(output).contains("skywright.deployment.environment", "must be a lowercase deployment identifier")
			.doesNotContain(sensitiveValue)
			.doesNotContain("Started SkywrightBackendApplication");
		command(Duration.ofSeconds(20), "rm", invalidContainer);
	}

	private void assertImageConfiguration() throws Exception {
		assertThat(inspect("{{.Config.User}}")).isEqualTo("10001:10001");
		assertThat(inspect("{{json .Config.Entrypoint}}"))
			.isEqualTo("[\"java\",\"-jar\",\"/opt/skywright/application.jar\"]");
		assertThat(inspect("{{index .Config.Labels \"org.opencontainers.image.version\"}}"))
			.isEqualTo(System.getProperty("backend.version"));
		assertThat(inspect("{{index .Config.Labels \"org.opencontainers.image.revision\"}}"))
			.isEqualTo(System.getProperty("backend.sourceRevision"));
		assertThat(Instant.parse(inspect("{{index .Config.Labels \"org.opencontainers.image.created\"}}"))).isNotNull();
		assertThat(inspect("{{json .Config.Env}}")).doesNotContain("SKYWRIGHT_DEPLOYMENT");

		var executable = Path.of(System.getProperty("backend.executable"));
		var expectedDigest = HexFormat.of()
			.formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(executable)));
		assertThat(success("run", "--rm", "--entrypoint", "sha256sum", image, "/opt/skywright/application.jar"))
			.startsWith(expectedDigest + " ");
	}

	private void assertRuntimeIdentity() throws Exception {
		assertThat(success("exec", container, "id", "-u")).isEqualTo("10001");
		assertThat(success("exec", container, "stat", "--format=%u:%g:%A", "/opt/skywright"))
			.isEqualTo("0:0:drwxr-xr-x");
		assertThat(success("exec", container, "stat", "--format=%u:%g:%A", "/opt/skywright/application.jar"))
			.isEqualTo("0:0:-rw-r--r--");
		assertThat(success("exec", container, "java", "-version"))
			.contains("OpenJDK Runtime Environment GraalVM CE 25.2.4+7.1", "build 25.0.4+7-jvmci-25.2-b20");
		assertThat(success("exec", container, "jcmd", "1", "VM.flags")).contains("ThreadStackSize=2048");
	}

	private void assertHttpBoundary(int port) throws Exception {
		var live = get(port, "/livez");
		var ready = get(port, "/readyz");
		var info = get(port, "/actuator/info");
		var openApi = get(port, "/openapi/skywright-api.yaml");

		assertThat(live.statusCode()).isEqualTo(200);
		assertThat(live.body()).contains("\"status\":\"UP\"");
		assertThat(ready.statusCode()).isEqualTo(200);
		assertThat(ready.body()).contains("\"status\":\"UP\"");
		assertThat(info.statusCode()).isEqualTo(200);
		assertThat(info.body())
			.contains("\"version\":\"" + System.getProperty("backend.version") + "\"",
					"\"sourceRevision\":\"" + System.getProperty("backend.sourceRevision") + "\"")
			.doesNotContain("acceptance");
		assertThat(openApi.statusCode()).isEqualTo(200);
		var canonicalContract = Path.of(System.getProperty("backend.reactorDirectory"), "api", "skywright-api", "src",
				"main", "resources", "META-INF", "openapi", "skywright-api.yaml");
		assertThat(openApi.body()).isEqualTo(Files.readString(canonicalContract));
	}

	private void assertStructuredRequestLog(int port) throws Exception {
		var correlationId = "container-smoke-correlation";
		var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/readyz"))
			.header("X-Correlation-ID", correlationId)
			.GET()
			.build();
		assertThat(HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode())
			.isEqualTo(200);

		var event = awaitLogEvent(node -> correlationId.equals(node.path("correlationId").asText()),
				Duration.ofSeconds(5));
		assertThat(event.path("http").path("request").path("method").asText()).isEqualTo("GET");
		assertThat(event.path("http").path("route").asText()).isEqualTo("/readyz");
		assertThat(event.path("http").path("response").path("status_code").asInt()).isEqualTo(200);
		assertThat(stdoutLines()).isNotEmpty().allSatisfy(line -> assertThat(JSON.readTree(line)).isNotNull());
	}

	private void assertGracefulTermination(int port) throws Exception {
		var requests = new ArrayList<CompletableFuture<HttpResponse<String>>>();
		var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
		for (var index = 0; index < 500; index++) {
			var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/openapi/skywright-api.yaml"))
				.header("X-Correlation-ID", "shutdown-load-" + index)
				.GET()
				.build();
			requests.add(client.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
		}
		awaitLogEvent(node -> node.path("correlationId").asText().startsWith("shutdown-load-"), Duration.ofSeconds(10));

		var stopStarted = Instant.now();
		var stop = command(Duration.ofSeconds(15), "stop", "--time", "10", container);
		var stopDuration = Duration.between(stopStarted, Instant.now());
		assertThat(stop.exitCode()).as(stop.output()).isZero();
		assertThat(stopDuration).isLessThan(Duration.ofSeconds(10));
		awaitAttachedContainerExit(Duration.ofSeconds(5));
		assertThat(stdoutLines()).allSatisfy(line -> assertThat(parseJson(line)).isNotNull());

		var events = stdoutLines().stream().map(this::parseJson).toList();
		var refusing = indexOf(events, node -> "REFUSING_TRAFFIC".equals(node.path("readinessState").asText()));
		var shutdownStarted = indexOf(events,
				node -> node.path("message").asText().startsWith("Commencing graceful shutdown"));
		var shutdownCompleted = indexOf(events,
				node -> node.path("message").asText().equals("Graceful shutdown complete"));
		assertThat(refusing).isBetween(0, shutdownStarted);
		assertThat(shutdownStarted).isLessThan(shutdownCompleted);
		assertThat(events.subList(shutdownStarted + 1, shutdownCompleted)
			.stream()
			.anyMatch(node -> node.path("correlationId").asText().startsWith("shutdown-load-"))).isTrue();

		var completedRequests = requests.stream()
			.filter(CompletableFuture::isDone)
			.filter(future -> !future.isCompletedExceptionally())
			.map(CompletableFuture::join)
			.filter(response -> response.statusCode() == 200)
			.count();
		var refusedRequests = requests.stream().filter(CompletableFuture::isCompletedExceptionally).count();
		assertThat(completedRequests).isPositive();
		assertThat(refusedRequests).isPositive();
		assertThatThrownByRequest(port);
		assertThat(success("inspect", "--format", "{{.State.Running}}", container)).isEqualTo("false");
		runningContainer = null;
		command(Duration.ofSeconds(20), "rm", container);
	}

	private JsonNode awaitLogEvent(Predicate<JsonNode> matches, Duration timeout) throws Exception {
		var deadline = Instant.now().plus(timeout);
		while (Instant.now().isBefore(deadline)) {
			for (var line : stdoutLines()) {
				try {
					var event = JSON.readTree(line);
					if (matches.test(event)) {
						return event;
					}
				}
				catch (RuntimeException ignored) {
					// The structured-output assertion reports the malformed line.
				}
			}
			Thread.sleep(Duration.ofMillis(50));
		}
		throw new AssertionError("No matching structured application log found");
	}

	private void assertThatThrownByRequest(int port) {
		try {
			get(port, "/readyz");
			throw new AssertionError("Backend accepted new HTTP work after graceful shutdown");
		}
		catch (IOException expected) {
			// The application port refuses new work after shutdown.
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Interrupted while verifying refused work", exception);
		}
	}

	private void startAttachedContainer(String... arguments) throws IOException {
		var command = new ArrayList<String>();
		command.add(runtime);
		command.addAll(List.of("run", "--name", container));
		command.addAll(Arrays.asList(arguments));
		command.add(image);
		attachedContainer = new ProcessBuilder(command).directory(moduleDirectory.toFile()).start();
		standardOutputReader = readLines(attachedContainer.getInputStream(), standardOutput);
		standardErrorReader = readLines(attachedContainer.getErrorStream(), standardError);
	}

	private void awaitAttachedContainerExit(Duration timeout) throws Exception {
		assertThat(attachedContainer.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
		standardOutputReader.join(timeout);
		standardErrorReader.join(timeout);
		assertThat(attachedContainer.exitValue()).as(String.join("\n", standardError)).isIn(0, 143);
	}

	private static Thread readLines(java.io.InputStream input, List<String> destination) {
		return Thread.ofPlatform().daemon().start(() -> {
			try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
				reader.lines().forEach(destination::add);
			}
			catch (IOException exception) {
				throw new IllegalStateException("Could not capture container output", exception);
			}
		});
	}

	private List<String> stdoutLines() {
		synchronized (standardOutput) {
			return standardOutput.stream().filter(line -> !line.isBlank()).toList();
		}
	}

	private JsonNode parseJson(String line) {
		try {
			return JSON.readTree(line);
		}
		catch (RuntimeException exception) {
			throw new AssertionError("Application stdout was not structured JSON: " + line, exception);
		}
	}

	private static int indexOf(List<JsonNode> events, Predicate<JsonNode> matches) {
		for (var index = 0; index < events.size(); index++) {
			if (matches.test(events.get(index))) {
				return index;
			}
		}
		return -1;
	}

	private HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
		var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build();
		return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
	}

	private static int availablePort() throws IOException {
		try (var socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	private static void awaitReadiness(int port, Duration timeout) throws InterruptedException {
		var client = HttpClient.newHttpClient();
		var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/readyz")).GET().build();
		var deadline = Instant.now().plus(timeout);
		while (Instant.now().isBefore(deadline)) {
			try {
				var response = client.send(request, HttpResponse.BodyHandlers.discarding());
				if (response.statusCode() == 200) {
					return;
				}
			}
			catch (IOException ignored) {
				// The container has not bound its published HTTP socket yet.
			}
			Thread.sleep(Duration.ofMillis(50));
		}
		throw new AssertionError("Backend image did not become ready within " + timeout);
	}

	private String inspect(String format) throws Exception {
		return success("image", "inspect", "--format", format, image);
	}

	private String success(String... arguments) throws Exception {
		var result = command(COMMAND_TIMEOUT, arguments);
		assertThat(result.exitCode()).as(result.output()).isZero();
		return result.output().trim();
	}

	private CommandResult command(Duration timeout, String... arguments) throws Exception {
		var command = new ArrayList<String>();
		command.add(runtime);
		command.addAll(Arrays.asList(arguments));
		var process = new ProcessBuilder(command).directory(moduleDirectory.toFile()).redirectErrorStream(true).start();
		var output = CompletableFuture.supplyAsync(() -> readOutput(process));
		if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
			process.destroyForcibly();
			throw new AssertionError("Container command timed out: " + String.join(" ", command));
		}
		return new CommandResult(process.exitValue(), output.get(10, TimeUnit.SECONDS));
	}

	private static String readOutput(Process process) {
		try {
			return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException exception) {
			throw new IllegalStateException("Could not read container command output", exception);
		}
	}

	private record CommandResult(int exitCode, String output) {
	}

}
