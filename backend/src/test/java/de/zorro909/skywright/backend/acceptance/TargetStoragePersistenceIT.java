package de.zorro909.skywright.backend.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("real-service")
final class TargetStoragePersistenceIT {

	@Test
	void concurrentRegistrationsCannotClaimTheSameResource() throws Exception {
		try (var database = PostgreSqlFixture.freshDatabase()) {
			int port = BackendProcess.availablePort();
			try (var backend = BackendProcess.start(arguments(database, port));
					var requests = Executors.newVirtualThreadPerTaskExecutor()) {
				awaitReady(backend, port);
				var barrier = new CyclicBarrier(2);
				var first = requests.submit(() -> {
					barrier.await();
					return request(port, "POST", "/api/v1/target-storages",
							registration().replace("Acceptance outputs", "Concurrent output A"));
				});
				var second = requests.submit(() -> {
					barrier.await();
					return request(port, "POST", "/api/v1/target-storages",
							registration().replace("Acceptance outputs", "Concurrent output B"));
				});

				var responses = List.of(first.get(), second.get())
					.stream()
					.sorted(Comparator.comparingInt(HttpResponse::statusCode))
					.toList();
				assertThat(responses).extracting(HttpResponse::statusCode).containsExactly(201, 409);
				assertThat(responses.get(1).body()).as(backend.output())
					.contains("SKYWRIGHT_TARGET_STORAGE_RESOURCE_CONFLICT");
			}
		}
	}

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
				var qualification = request(firstPort, "POST",
						"/api/v1/target-storages/" + storageId + "/qualification", null);
				assertThat(qualification.statusCode()).as(backend.output()).isEqualTo(200);
				assertThat(qualification.body()).contains("transiently-unavailable");
				var revision = request(firstPort, "POST", "/api/v1/target-storages/" + storageId + "/revisions",
						revision());
				assertThat(revision.statusCode()).as(backend.output()).isEqualTo(200);
				assertThat(revision.body()).contains("replacement.example", "\"candidateRevision\":2");
			}

			int secondPort = BackendProcess.availablePort();
			try (var backend = BackendProcess.start(arguments(database, secondPort))) {
				awaitReady(backend, secondPort);
				var response = request(secondPort, "GET", "/api/v1/target-storages/" + storageId, null);
				assertThat(response.statusCode()).as(backend.output()).isEqualTo(200);
				assertThat(response.body()).contains("Acceptance outputs", "acceptance-runs",
						"credential-binding-unavailable", "replacement.example");
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

	private static String revision() {
		return """
				{
				  "expectedRegistrationRevision": 2,
				  "configuration": {
				    "endpoint": "http://replacement.example",
				    "region": "us-east-1",
				    "pathStyleAccess": true,
				    "compatibilityOptions": {}
				  }
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
