package de.zorro909.skywright.backend.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class StructuredLoggingIT {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@TempDir
	Path runtimeDirectory;

	@Test
	void correlatedRequestIsRecordedAsSafeStructuredConsoleEvent() throws Exception {
		var port = BackendProcess.availablePort();
		var correlationId = "acceptance-correlation";
		try (var backend = BackendProcess.start(runtimeDirectory, Map.of(), List.of(), "--server.port=" + port,
				"--skywright.deployment.environment=acceptance")) {
			BackendProcess.awaitReadiness(port, Duration.ofSeconds(20));
			var request = HttpRequest
				.newBuilder(URI.create("http://127.0.0.1:" + port + "/readyz?key=hidden-query-value"))
				.header("X-Correlation-ID", correlationId)
				.header("X-Sensitive", "hidden-header-value")
				.GET()
				.build();
			var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding());

			assertThat(response.statusCode()).isEqualTo(200);
			var event = awaitEvent(backend, correlationId, Duration.ofSeconds(5));
			assertThat(event.path("http").path("request").path("method").asText()).isEqualTo("GET");
			assertThat(event.path("http").path("route").asText()).isEqualTo("/readyz");
			assertThat(event.path("http").path("response").path("status_code").asInt()).isEqualTo(200);
			assertThat(event.path("event").path("duration").asLong()).isPositive();
			assertThat(Instant.parse(event.path("@timestamp").asText())).isNotNull();
			assertThat(event.path("log").path("level").asText()).isEqualTo("INFO");
			assertThat(event.path("log").path("logger").asText()).isNotBlank();
			assertThat(event.path("process").path("thread").path("name").asText()).isNotBlank();
			assertThat(event.path("service").path("name").asText()).isEqualTo("skywright-backend");
			assertThat(event.path("service").path("version").asText()).isEqualTo("0.1.0-SNAPSHOT");
			assertThat(event.path("message").asText()).isNotBlank();
			assertThat(backend.output()).doesNotContain("hidden-query-value", "hidden-header-value");
			assertThat(lines(backend))
				.allSatisfy(line -> assertThatCode(() -> JSON.readTree(line)).doesNotThrowAnyException());
		}

		try (var runtimeFiles = Files.list(runtimeDirectory)) {
			assertThat(runtimeFiles).isEmpty();
		}
	}

	@Test
	void localProfileUsesReadableConsoleWithoutChangingReadiness() throws Exception {
		var port = BackendProcess.availablePort();
		try (var backend = BackendProcess.start(runtimeDirectory, Map.of(), List.of(), "--server.port=" + port,
				"--skywright.deployment.environment=local", "--spring.profiles.active=local")) {
			BackendProcess.awaitReadiness(port, Duration.ofSeconds(20));

			assertThat(backend.isAlive()).isTrue();
			assertThat(backend.output()).contains(" INFO ").doesNotContain("\"log\":{\"level\"");
		}
	}

	private static JsonNode awaitEvent(BackendProcess backend, String correlationId, Duration timeout)
			throws Exception {
		var deadline = Instant.now().plus(timeout);
		while (Instant.now().isBefore(deadline)) {
			for (var line : lines(backend)) {
				try {
					var event = JSON.readTree(line);
					if (correlationId.equals(event.path("correlationId").asText())) {
						return event;
					}
				}
				catch (RuntimeException ignored) {
					// A red test can observe the pre-structured logging format.
				}
			}
			Thread.sleep(Duration.ofMillis(25));
		}
		throw new AssertionError("No request log event found for correlation " + correlationId);
	}

	private static List<String> lines(BackendProcess backend) {
		return Arrays.stream(backend.output().split("\\R")).filter(line -> !line.isBlank()).toList();
	}

}
