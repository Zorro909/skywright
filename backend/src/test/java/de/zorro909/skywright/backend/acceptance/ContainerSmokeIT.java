package de.zorro909.skywright.backend.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
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
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "backend.container.enabled", matches = "true")
final class ContainerSmokeIT {

	private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(2);

	private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(30);

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final String runtime = System.getProperty("backend.container.runtime");

	private final String image = System.getProperty("backend.container.image") + "-smoke-"
			+ UUID.randomUUID().toString().substring(0, 8);

	private final String container = "skywright-backend-smoke-" + UUID.randomUUID().toString().substring(0, 8);

	private final Path moduleDirectory = Path.of(System.getProperty("backend.moduleDirectory"));

	private String runningContainer;

	@BeforeAll
	void buildProductionImage() throws Exception {
		var result = command(COMMAND_TIMEOUT, "build", "--file", "src/main/docker/Dockerfile", "--tag", image,
				"--build-arg", "JAR_FILE=target/skywright-backend-" + System.getProperty("backend.version") + ".jar",
				"--build-arg", "APPLICATION_VERSION=" + System.getProperty("backend.version"), "--build-arg",
				"SOURCE_REVISION=" + System.getProperty("backend.sourceRevision"), "--build-arg",
				"BUILD_CREATED=" + System.getProperty("backend.container.buildTime"), ".");

		assertThat(result.exitCode()).as(result.output()).isZero();
	}

	@AfterAll
	void removeSmokeArtifacts() throws Exception {
		if (runningContainer != null) {
			command(Duration.ofSeconds(20), "rm", "--force", runningContainer);
		}
		command(Duration.ofSeconds(20), "image", "rm", "--force", image);
	}

	@Test
	void productionImageRunsThroughItsOperatorBoundary() throws Exception {
		assertImageConfiguration();
		var port = BackendProcess.availablePort();
		var start = command(COMMAND_TIMEOUT, "run", "--detach", "--name", container, "--read-only", "--tmpfs",
				"/tmp:rw,noexec,nosuid,size=64m", "--env", "SKYWRIGHT_DEPLOYMENT_ENVIRONMENT=acceptance", "--env",
				"JAVA_TOOL_OPTIONS=-Xss2m", "--publish", "127.0.0.1:" + port + ":8080", image);
		assertThat(start.exitCode()).as(start.output()).isZero();
		runningContainer = container;
		BackendProcess.awaitReadiness(port, STARTUP_TIMEOUT);

		assertRuntimeIdentity();
		assertHttpBoundary(port);
		assertStructuredRequestLog(port);
		assertGracefulTermination();
	}

	@Test
	void invalidExternalConfigurationStopsTheContainerWithASanitizedDiagnostic() throws Exception {
		var sensitiveValue = "production-private-container-token!";
		var result = command(COMMAND_TIMEOUT, "run", "--rm", "--read-only", "--tmpfs", "/tmp:rw,noexec,nosuid,size=64m",
				"--env", "SKYWRIGHT_DEPLOYMENT_ENVIRONMENT=" + sensitiveValue, image);

		assertThat(result.exitCode()).isNotZero();
		assertThat(result.output())
			.contains("skywright.deployment.environment", "must be a lowercase deployment identifier")
			.doesNotContain(sensitiveValue)
			.doesNotContain("Started SkywrightBackendApplication");
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

		var executable = moduleDirectory
			.resolve("target/skywright-backend-" + System.getProperty("backend.version") + ".jar");
		var expectedDigest = HexFormat.of()
			.formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(executable)));
		assertThat(success("run", "--rm", "--entrypoint", "sha256sum", image, "/opt/skywright/application.jar"))
			.startsWith(expectedDigest + " ");
	}

	private void assertRuntimeIdentity() throws Exception {
		assertThat(success("exec", container, "id", "-u")).isEqualTo("10001");
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

		var event = awaitLogEvent(correlationId, Duration.ofSeconds(5));
		assertThat(event.path("http").path("request").path("method").asText()).isEqualTo("GET");
		assertThat(event.path("http").path("route").asText()).isEqualTo("/readyz");
		assertThat(event.path("http").path("response").path("status_code").asInt()).isEqualTo(200);
	}

	private void assertGracefulTermination() throws Exception {
		var stop = command(Duration.ofSeconds(25), "stop", "--time", "20", container);
		assertThat(stop.exitCode()).as(stop.output()).isZero();
		var logs = success("logs", container);
		assertThat(logs).contains("Commencing graceful shutdown", "Graceful shutdown complete");
		assertThat(success("inspect", "--format", "{{.State.Running}}", container)).isEqualTo("false");
		runningContainer = null;
		command(Duration.ofSeconds(20), "rm", container);
	}

	private JsonNode awaitLogEvent(String correlationId, Duration timeout) throws Exception {
		var deadline = Instant.now().plus(timeout);
		while (Instant.now().isBefore(deadline)) {
			for (var line : success("logs", container).split("\\R")) {
				try {
					var event = JSON.readTree(line);
					if (correlationId.equals(event.path("correlationId").asText())) {
						return event;
					}
				}
				catch (RuntimeException ignored) {
					// Container-runtime diagnostics are not application log lines.
				}
			}
			Thread.sleep(Duration.ofMillis(50));
		}
		throw new AssertionError("No structured request log found for " + correlationId);
	}

	private HttpResponse<String> get(int port, String path) throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build();
		return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
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
