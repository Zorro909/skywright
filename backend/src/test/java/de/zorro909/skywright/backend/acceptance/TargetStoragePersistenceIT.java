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
final class TargetStoragePersistenceIT {

	@Test
	void registrationSurvivesAControlPlaneRestartWithoutPersistingCredentialValues() throws Exception {
		try (var database = PostgreSqlFixture.freshDatabase()) {
			String storageId;
			int firstPort = BackendProcess.availablePort();
			try (var backend = BackendProcess.start(arguments(database, firstPort))) {
				awaitReady(backend, firstPort);
				var response = request(firstPort, "POST", "/api/v1/target-storages", registration());
				assertThat(response.statusCode()).as(backend.output()).isEqualTo(201);
				assertThat(response.body()).doesNotContain("test-secret", "credentialValue", "accessKey");
				storageId = response.body().replaceFirst("(?s).*\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");
			}

			int secondPort = BackendProcess.availablePort();
			try (var backend = BackendProcess.start(arguments(database, secondPort))) {
				awaitReady(backend, secondPort);
				var response = request(secondPort, "GET", "/api/v1/target-storages/" + storageId, null);
				assertThat(response.statusCode()).as(backend.output()).isEqualTo(200);
				assertThat(response.body()).contains("Acceptance outputs", "acceptance-runs");
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

	private static String registration() {
		return """
				{
				  "name": "Acceptance outputs",
				  "purpose": "run-output",
				  "bucket": "acceptance-runs",
				  "configuration": {
				    "endpoint": "http://storage.example",
				    "region": "us-east-1",
				    "pathStyleAccess": true,
				    "compatibilityOptions": {}
				  },
				  "bindings": [
				    {"role":"training-process","bindingId":"00000000-0000-0000-0000-000000000001","bindingRevision":1},
				    {"role":"backend","bindingId":"00000000-0000-0000-0000-000000000002","bindingRevision":1},
				    {"role":"transfer-worker","bindingId":"00000000-0000-0000-0000-000000000003","bindingRevision":1},
				    {"role":"metric-view","bindingId":"00000000-0000-0000-0000-000000000004","bindingRevision":1}
				  ]
				}
				""";
	}

	private static String[] arguments(PostgreSqlFixture.Database database, int port) {
		var databaseArguments = database.backendArguments();
		var arguments = new String[databaseArguments.size() + 2];
		arguments[0] = "--server.port=" + port;
		arguments[1] = "--skywright.deployment.environment=test";
		for (var index = 0; index < databaseArguments.size(); index++) {
			arguments[index + 2] = databaseArguments.get(index);
		}
		return arguments;
	}

}
