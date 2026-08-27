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
final class TrainingProjectApiIT {

	@Test
	void operatorCreatesInspectsListsAndRenamesAProjectWithOptimisticConflictProtection() throws Exception {
		try (var database = PostgreSqlFixture.freshDatabase()) {
			int port = BackendProcess.availablePort();
			try (var backend = BackendProcess.start(arguments(database, port))) {
				awaitReady(backend, port);
				var created = request(port, "POST", "/api/v1/training-projects", """
						{
						  "displayName":"  Vision Lab  ",
						  "registry":{"repository":"ghcr.io/example/vision","accessMode":"public"}
						}
						""");
				assertThat(created.statusCode()).as(backend.output()).isEqualTo(201);
				assertThat(created.body()).contains("\"displayName\":\"Vision Lab\"", "\"revision\":1",
						"\"repository\":\"ghcr.io/example/vision\"", "\"readiness\":\"ready\"");
				String projectId = jsonString(created.body(), "id");
				assertThat(projectId.charAt(14)).isEqualTo('4');

				var inspected = request(port, "GET", "/api/v1/training-projects/" + projectId, null);
				assertThat(inspected.statusCode()).isEqualTo(200);
				assertThat(inspected.body()).contains(projectId, "Vision Lab");

				var listed = request(port, "GET", "/api/v1/training-projects", null);
				assertThat(listed.statusCode()).isEqualTo(200);
				assertThat(listed.body()).contains(projectId, "Vision Lab");

				var renamed = request(port, "PUT", "/api/v1/training-projects/" + projectId + "/display-name",
						"{\"expectedRevision\":1,\"displayName\":\"Perception\"}");
				assertThat(renamed.statusCode()).isEqualTo(200);
				assertThat(renamed.body()).contains("\"displayName\":\"Perception\"", "\"revision\":2");

				var stale = request(port, "PUT", "/api/v1/training-projects/" + projectId + "/display-name",
						"{\"expectedRevision\":1,\"displayName\":\"Stale\"}");
				assertThat(stale.statusCode()).isEqualTo(409);
				assertThat(stale.body()).contains("SKYWRIGHT_TRAINING_PROJECT_REVISION_CONFLICT");

				var duplicateName = request(port, "POST", "/api/v1/training-projects",
						"""
								{"displayName":" perception ","registry":{"repository":"ghcr.io/example/other","accessMode":"public"}}
								""");
				assertThat(duplicateName.statusCode()).isEqualTo(409);
				assertThat(duplicateName.body()).contains("SKYWRIGHT_TRAINING_PROJECT_NAME_CONFLICT");

				var duplicateRepository = request(port, "POST", "/api/v1/training-projects", """
						{"displayName":"Other","registry":{"repository":"ghcr.io/example/vision","accessMode":"public"}}
						""");
				assertThat(duplicateRepository.statusCode()).isEqualTo(409);
				assertThat(duplicateRepository.body()).contains("SKYWRIGHT_TRAINING_PROJECT_REPOSITORY_CONFLICT");
			}
		}
	}

	@Test
	void incompletePrivateBindingStaysInspectableAndCannotResolveVersions() throws Exception {
		try (var database = PostgreSqlFixture.freshDatabase()) {
			int port = BackendProcess.availablePort();
			try (var backend = BackendProcess.start(arguments(database, port))) {
				awaitReady(backend, port);
				var created = request(port, "POST", "/api/v1/training-projects", """
						{"displayName":"Private","registry":{"repository":"ghcr.io/example/private"}}
						""");
				assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
				assertThat(created.body()).contains("\"accessMode\":\"private\"", "\"readiness\":\"missing\"")
					.doesNotContain("credentialValue", "authorization", "token");
				String projectId = jsonString(created.body(), "id");

				var unavailable = request(port, "GET", "/api/v1/training-projects/" + projectId + "/versions", null);
				assertThat(unavailable.statusCode()).isEqualTo(422);
				assertThat(unavailable.body()).contains("SKYWRIGHT_TRAINING_PROJECT_CREDENTIALS_UNAVAILABLE");

				var replaced = request(port, "PUT", "/api/v1/training-projects/" + projectId + "/registry-credentials",
						"""
								{
								  "expectedRevision":1,
								  "resolverCredentialBindingId":"00000000-0000-0000-0000-000000000001",
								  "executionCredentialBindingId":"00000000-0000-0000-0000-000000000002"
								}
								""");
				assertThat(replaced.statusCode()).as(replaced.body()).isEqualTo(200);
				assertThat(replaced.body()).contains("\"revision\":2", "\"state\":\"retired\"", "\"state\":\"active\"",
						"\"readiness\":\"missing\"");
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
		return body.replaceFirst("(?s).*?\\\"" + field + "\\\":\\\"([^\\\"]+)\\\".*", "$1");
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
