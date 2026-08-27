package de.zorro909.skywright.backend.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("real-service")
final class TrainingProjectPersistenceIT {

	@Test
	void projectBindingHistoryAndFailedRebindingSurviveAControlPlaneRestart() throws Exception {
		try (var database = PostgreSqlFixture.freshDatabase()) {
			String projectId;
			String operationId;
			int firstPort = BackendProcess.availablePort();
			try (var backend = BackendProcess.start(arguments(database, firstPort))) {
				awaitReady(backend, firstPort);
				var created = request(firstPort, "POST", "/api/v1/training-projects",
						"""
								{"displayName":"Persistent","registry":{"repository":"ghcr.io/example/persistent","accessMode":"public"}}
								""");
				assertThat(created.statusCode()).as(backend.output()).isEqualTo(201);
				projectId = jsonString(created.body(), "id");
				var started = request(firstPort, "POST",
						"/api/v1/training-projects/" + projectId + "/registry-rebindings",
						"""
								{"expectedRevision":1,"candidate":{"repository":"ghcr.io/example/candidate","accessMode":"private"}}
								""");
				assertThat(started.statusCode()).as(started.body()).isEqualTo(201);
				assertThat(started.body()).contains("\"state\":\"failed\"", "REGISTRY_CREDENTIALS_UNAVAILABLE");
				operationId = jsonString(started.body(), "id");

				var credentialChange = request(firstPort, "PUT",
						"/api/v1/training-projects/" + projectId + "/registry-credentials", """
								{
								  "expectedRevision":2,
								  "resolverCredentialBindingId":"00000000-0000-0000-0000-000000000001",
								  "executionCredentialBindingId":"00000000-0000-0000-0000-000000000002"
								}
								""");
				assertThat(credentialChange.statusCode()).as(credentialChange.body()).isEqualTo(409);
				assertThat(credentialChange.body()).contains("SKYWRIGHT_REGISTRY_REBINDING_CONFLICT");

				var competing = request(firstPort, "POST",
						"/api/v1/training-projects/" + projectId + "/registry-rebindings",
						"""
								{"expectedRevision":2,"candidate":{"repository":"ghcr.io/example/competing","accessMode":"public"}}
								""");
				assertThat(competing.statusCode()).as(competing.body()).isEqualTo(409);
				assertThat(competing.body()).contains("SKYWRIGHT_REGISTRY_REBINDING_CONFLICT");
			}

			int secondPort = BackendProcess.availablePort();
			try (var backend = BackendProcess.start(arguments(database, secondPort))) {
				awaitReady(backend, secondPort);
				var project = request(secondPort, "GET", "/api/v1/training-projects/" + projectId, null);
				assertThat(project.statusCode()).as(backend.output()).isEqualTo(200);
				assertThat(project.body()).contains("Persistent", "ghcr.io/example/persistent",
						"ghcr.io/example/candidate", "\"state\":\"candidate\"");
				var operation = request(secondPort, "GET",
						"/api/v1/training-projects/" + projectId + "/registry-rebindings/" + operationId, null);
				assertThat(operation.statusCode()).as(operation.body()).isEqualTo(200);
				assertThat(operation.body()).contains("\"state\":\"failed\"", "\"attempts\":1");
				var abandoned = request(secondPort, "POST",
						"/api/v1/training-projects/" + projectId + "/registry-rebindings/" + operationId + "/abandon",
						"{\"expectedRevision\":2}");
				assertThat(abandoned.statusCode()).as(abandoned.body()).isEqualTo(200);
				assertThat(abandoned.body()).contains("\"state\":\"abandoned\"", "\"attempts\":1", "\"completedAt\"");
				var releasedCandidate = request(secondPort, "POST", "/api/v1/training-projects",
						"""
								{"displayName":"Released","registry":{"repository":"ghcr.io/example/candidate","accessMode":"public"}}
								""");
				assertThat(releasedCandidate.statusCode()).as(releasedCandidate.body()).isEqualTo(201);
			}
		}
	}

	private static void awaitReady(BackendProcess backend, int port) throws Exception {
		try {
			BackendProcess.awaitReadiness(port, Duration.ofSeconds(30));
		}
		catch (AssertionError failure) {
			throw new AssertionError(failure.getMessage() + "\n" + backend.output(), failure);
		}
	}

	private static HttpResponse<String> request(int port, String method, String path, String body) throws Exception {
		var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
			.timeout(Duration.ofSeconds(5));
		if (body == null) {
			builder.method(method, HttpRequest.BodyPublishers.noBody());
		}
		else {
			builder.header("Content-Type", "application/json")
				.method(method, HttpRequest.BodyPublishers.ofString(body));
		}
		return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static String jsonString(String body, String field) {
		return body.replaceFirst("(?s).*?\"" + field + "\":\"([^\"]+)\".*", "$1");
	}

	private static String[] arguments(PostgreSqlFixture.Database database, int port) {
		var databaseArguments = database.backendArguments();
		var arguments = new String[databaseArguments.size() + 3];
		arguments[0] = "--server.port=" + port;
		arguments[1] = "--skywright.deployment.environment=test";
		arguments[2] = "--skywright.deployment.reporting-currency=EUR";
		for (var index = 0; index < databaseArguments.size(); index++) {
			arguments[index + 3] = databaseArguments.get(index);
		}
		return arguments;
	}

}
