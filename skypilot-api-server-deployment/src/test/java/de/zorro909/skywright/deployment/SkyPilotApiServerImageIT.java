package de.zorro909.skywright.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.json.JsonMapper;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
final class SkyPilotApiServerImageIT {

	private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(3);

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

	private final String databasePassword = "db-" + UUID.randomUUID();

	private String network;

	private String databaseContainer;

	private String stateVolume;

	private String serverContainer;

	private URI baseUri;

	@BeforeAll
	void startProductionImage() throws Exception {
		network = name("network");
		databaseContainer = name("database");
		stateVolume = name("state");
		serverContainer = name("server");
		docker("network", "create", network);
		docker("volume", "create", stateVolume);
		docker("run", "--detach", "--name", databaseContainer, "--network", network, "--env", "POSTGRES_DB=skypilot",
				"--env", "POSTGRES_USER=skypilot", "--env", "POSTGRES_PASSWORD=" + databasePassword, postgresqlImage());
		awaitPostgreSql(databaseContainer, STARTUP_TIMEOUT);

		docker(serverArguments(serverContainer).toArray(String[]::new));
		var port = awaitPublishedPort(serverContainer, STARTUP_TIMEOUT);
		baseUri = URI.create("http://127.0.0.1:" + port);
		awaitHealth(STARTUP_TIMEOUT);
	}

	@AfterAll
	void cleanUp() {
		removeContainer(serverContainer);
		removeContainer(databaseContainer);
		if (stateVolume != null) {
			dockerIgnoringFailure("volume", "rm", stateVolume);
		}
		if (network != null) {
			dockerIgnoringFailure("network", "rm", network);
		}
	}

	@Test
	@Order(1)
	void productionArtifactHasTheExpectedIdentityAndRuntimeContract() throws Exception {
		var health = JSON.readTree(get("/api/health"));
		assertThat(health.path("status").asText()).isEqualTo("healthy");
		assertThat(health.path("version").asText()).isEqualTo(skyPilotVersion());
		assertThat(health.path("version_on_disk").asText()).isEqualTo(skyPilotVersion());

		var pidOneCommand = docker("exec", serverContainer, "python", "-c",
				"from pathlib import Path; print(Path('/proc/1/cmdline').read_bytes().replace(b'\\0', b' ').decode())");
		assertThat(pidOneCommand).contains("python -m sky.server.server", "--host=0.0.0.0", "--port=46580");

		assertThat(inspectImage("{{.Config.User}}").strip()).isEqualTo("10002:10002");
		assertThat(inspectImage("{{index .Config.Labels \"org.opencontainers.image.revision\"}}").strip())
			.isEqualTo(sourceRevision());
		assertThat(inspectImage("{{index .Config.Labels \"org.opencontainers.image.version\"}}").strip())
			.isEqualTo("0.1.0-SNAPSHOT");
		assertThat(inspectImage("{{index .Config.Labels \"io.skywright.python.version\"}}").strip())
			.isEqualTo(pythonVersion());
		assertThat(inspectImage("{{index .Config.Labels \"io.skywright.skypilot.version\"}}").strip())
			.isEqualTo(skyPilotVersion());
		assertThat(inspectImage("{{index .Config.Labels \"org.opencontainers.image.base.digest\"}}").strip())
			.isEqualTo("sha256:519591d6871b7bc437060736b9f7456b8731f1499a57e22e6c285135ae657bf7");
		assertThat(inspectImage("{{json .Config.Env}}").strip()).contains("OPENBLAS_NUM_THREADS=1")
			.doesNotContain("SKYPILOT_DB_CONNECTION_URI", "postgresql://");
		assertThat(docker("logs", serverContainer)).doesNotContain(databasePassword);
	}

	@Test
	@Order(2)
	void missingExternalDatabaseConfigurationFailsSafely() throws Exception {
		var container = name("missing-database");
		try {
			docker("create", "--name", container, "--read-only", "--tmpfs", "/tmp:rw,exec,nosuid,size=64m", "--volume",
					stateVolume + ":/var/lib/skypilot", "--env", "PYTHONPATH=/tmp/operator-override", imageName());
			docker("start", container);

			assertThat(awaitContainerExit(container, Duration.ofSeconds(10))).isEqualTo("78");
			assertThat(dockerCombinedOutput("logs", container)).contains("SKYPILOT_DB_CONNECTION_URI is required");
		}
		finally {
			removeContainer(container);
		}

		var invalidContainer = name("invalid-database");
		var sensitiveValue = "private-" + UUID.randomUUID();
		try {
			docker("create", "--name", invalidContainer, "--read-only", "--tmpfs", "/tmp:rw,exec,nosuid,size=64m",
					"--volume", stateVolume + ":/var/lib/skypilot", "--env",
					"SKYPILOT_DB_CONNECTION_URI=not-a-postgresql-uri-" + sensitiveValue, imageName());
			docker("start", invalidContainer);

			assertThat(awaitContainerExit(invalidContainer, Duration.ofSeconds(10))).isEqualTo("78");
			assertThat(dockerCombinedOutput("logs", invalidContainer))
				.contains("SKYPILOT_DB_CONNECTION_URI must be a PostgreSQL URI")
				.doesNotContain(sensitiveValue);
		}
		finally {
			removeContainer(invalidContainer);
		}

		var missingStateContainer = name("missing-state");
		var stateProbePassword = "state-" + UUID.randomUUID();
		try {
			docker("create", "--name", missingStateContainer, "--read-only", "--tmpfs", "/tmp:rw,exec,nosuid,size=64m",
					"--env", "SKYPILOT_DB_CONNECTION_URI=postgresql://skypilot:" + stateProbePassword
							+ "@unreachable.invalid/skypilot",
					imageName());
			docker("start", missingStateContainer);

			assertThat(awaitContainerExit(missingStateContainer, Duration.ofSeconds(10))).isEqualTo("78");
			assertThat(dockerCombinedOutput("logs", missingStateContainer))
				.contains("writable runtime path is unavailable: /var/lib/skypilot/.sky")
				.doesNotContain(stateProbePassword);
		}
		finally {
			removeContainer(missingStateContainer);
		}
	}

	@Test
	@Order(3)
	void publicDatabaseAndSubmittedFileStateSurviveContainerReplacement() throws Exception {
		var username = "restart-" + UUID.randomUUID();
		var userPassword = "user-" + UUID.randomUUID();
		var createUser = postJson("/users/create",
				"{\"username\":\"" + username + "\",\"password\":\"" + userPassword + "\",\"role\":\"user\"}");
		assertThat(createUser.statusCode()).withFailMessage("user creation failed: %s", createUser.body())
			.isEqualTo(200);

		var blobId = "a".repeat(64);
		var upload = postBytes(
				"/upload_v2?user_hash=restart-test&upload_id=" + blobId + "&chunk_index=0&total_chunks=1",
				zip("retained.txt", "survives replacement"));
		assertThat(upload.statusCode()).withFailMessage("blob upload failed: %s", upload.body()).isEqualTo(200);
		assertThat(upload.body()).contains("completed");
		assertThat(JSON.readTree(get("/upload_v2/blob?user_hash=restart-test&blob_id=" + blobId))
			.path("exists")
			.asBoolean()).isTrue();

		var stoppedContainer = serverContainer;
		var started = Instant.now();
		docker("stop", "--time", "25", stoppedContainer);
		assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(25));
		assertThat(docker("inspect", "--format", "{{.State.ExitCode}}", stoppedContainer).strip()).isIn("0", "143");
		assertThat(docker("inspect", "--format", "{{.State.Pid}}", stoppedContainer).strip()).isEqualTo("0");
		docker("rm", stoppedContainer);

		serverContainer = name("replacement");
		docker(serverArguments(serverContainer).toArray(String[]::new));
		var port = awaitPublishedPort(serverContainer, STARTUP_TIMEOUT);
		baseUri = URI.create("http://127.0.0.1:" + port);
		awaitHealth(STARTUP_TIMEOUT);

		var users = JSON.readTree(get("/users"));
		assertThat(users).anySatisfy(user -> assertThat(user.path("name").asText()).isEqualTo(username));
		assertThat(JSON.readTree(get("/upload_v2/blob?user_hash=restart-test&blob_id=" + blobId))
			.path("exists")
			.asBoolean()).isTrue();
		assertThat(docker("logs", serverContainer)).doesNotContain(databasePassword, userPassword);
	}

	private ArrayList<String> serverArguments(String container) {
		var databaseUri = "postgresql://skypilot:" + databasePassword + "@" + databaseContainer + ":5432/skypilot";
		return new ArrayList<>(List.of("run", "--detach", "--name", container, "--network", network, "--read-only",
				"--cpus", "2", "--memory", "6g", "--tmpfs", "/tmp:rw,exec,nosuid,size=256m", "--volume",
				stateVolume + ":/var/lib/skypilot", "--env", "SKYPILOT_DB_CONNECTION_URI=" + databaseUri, "--publish",
				"127.0.0.1::46580", imageName()));
	}

	private String get(String path) throws IOException, InterruptedException {
		var response = httpClient.send(HttpRequest.newBuilder(baseUri.resolve(path)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(200);
		return response.body();
	}

	private HttpResponse<String> postJson(String path, String body) throws IOException, InterruptedException {
		return httpClient.send(HttpRequest.newBuilder(baseUri.resolve(path))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> postBytes(String path, byte[] body) throws IOException, InterruptedException {
		return httpClient.send(HttpRequest.newBuilder(baseUri.resolve(path))
			.header("Content-Type", "application/zip")
			.POST(HttpRequest.BodyPublishers.ofByteArray(body))
			.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static byte[] zip(String name, String content) throws IOException {
		var bytes = new ByteArrayOutputStream();
		try (var zip = new ZipOutputStream(bytes)) {
			zip.putNextEntry(new ZipEntry(name));
			zip.write(content.getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return bytes.toByteArray();
	}

	private void awaitHealth(Duration timeout) throws Exception {
		var deadline = Instant.now().plus(timeout);
		while (Instant.now().isBefore(deadline)) {
			try {
				var response = httpClient.send(HttpRequest.newBuilder(baseUri.resolve("/api/health")).GET().build(),
						HttpResponse.BodyHandlers.ofString());
				if (response.statusCode() == 200 && response.body().contains("\"status\":\"healthy\"")) {
					return;
				}
			}
			catch (IOException ignored) {
				// The server is still starting.
			}
			Thread.sleep(Duration.ofMillis(250));
		}
		throw new AssertionError("SkyPilot did not become healthy:\n" + dockerCombinedOutput("logs", serverContainer));
	}

	private static int awaitPublishedPort(String container, Duration timeout) throws Exception {
		var deadline = Instant.now().plus(timeout);
		while (Instant.now().isBefore(deadline)) {
			var published = docker("port", container, "46580/tcp").strip();
			if (!published.isBlank()) {
				return Integer.parseInt(published.substring(published.lastIndexOf(':') + 1));
			}
			Thread.sleep(Duration.ofMillis(100));
		}
		throw new AssertionError("Container runtime did not publish port 46580");
	}

	private static void awaitPostgreSql(String container, Duration timeout) throws Exception {
		var deadline = Instant.now().plus(timeout);
		Instant continuouslyReadySince = null;
		while (Instant.now().isBefore(deadline)) {
			try {
				docker("exec", container, "pg_isready", "--username", "skypilot", "--dbname", "skypilot");
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
		throw new AssertionError("PostgreSQL did not become ready");
	}

	private static String awaitContainerExit(String container, Duration timeout) throws Exception {
		var deadline = Instant.now().plus(timeout);
		while (Instant.now().isBefore(deadline)) {
			var running = docker("inspect", "--format", "{{.State.Running}}", container).strip();
			if (running.equals("false")) {
				return docker("inspect", "--format", "{{.State.ExitCode}}", container).strip();
			}
			Thread.sleep(Duration.ofMillis(100));
		}
		throw new AssertionError("Container did not stop within " + timeout);
	}

	private static String inspectImage(String format) throws Exception {
		return docker("image", "inspect", "--format", format, imageName());
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
			throw new AssertionError(String.join(" ", command) + " exited " + exitCode + ":\n"
					+ (combineOutput ? standardOutput : errorOutput));
		}
		return standardOutput;
	}

	private static void removeContainer(String container) {
		if (container != null) {
			dockerIgnoringFailure("rm", "--force", container);
		}
	}

	private static void dockerIgnoringFailure(String... arguments) {
		try {
			docker(arguments);
		}
		catch (Exception | AssertionError ignored) {
			// Best-effort cleanup preserves the original test failure.
		}
	}

	private static String name(String purpose) {
		return "skywright-skypilot-" + purpose + "-" + UUID.randomUUID().toString().substring(0, 8);
	}

	private static String imageName() {
		return System.getProperty("image.name");
	}

	private static String postgresqlImage() {
		return System.getProperty("postgresql.container.image");
	}

	private static String sourceRevision() {
		return System.getProperty("source.revision");
	}

	private static String skyPilotVersion() {
		return System.getProperty("skypilot.version");
	}

	private static String pythonVersion() {
		return System.getProperty("python.version");
	}

}
