package de.zorro909.skywright.backend.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import de.zorro909.skywright.backend.SkywrightBackendApplication;
import de.zorro909.skywright.backend.trainingproject.TrainingProjectIntegrationTestConfiguration;
import de.zorro909.skywright.backend.trainingproject.ReferencedProjectArtifact;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

@Tag("real-service")
final class TrainingProjectVersionApiIT {

	@Test
	void liveDiscoveryDistinguishesEmptyAndUnavailableAndExactAssessmentDoesNotTrustEnumeration() throws Exception {
		try (var database = PostgreSqlFixture.freshDatabase(); var application = start(database)) {
			int port = ((WebServerApplicationContext) application).getWebServer().getPort();
			String availableId = create(port, "Available", "available");
			String emptyId = create(port, "Empty", "empty");
			String unavailableId = create(port, "Unavailable", "unavailable");

			var available = request(port, "GET", "/api/v1/training-projects/" + availableId + "/versions", null);
			assertThat(available.statusCode()).isEqualTo(200);
			assertThat(available.body()).contains("\"registryAvailable\":true", "github-81-1",
					"sha256:" + "9".repeat(64), "\"observedAt\"");

			var empty = request(port, "GET", "/api/v1/training-projects/" + emptyId + "/versions", null);
			assertThat(empty.statusCode()).isEqualTo(200);
			assertThat(empty.body()).contains("\"registryAvailable\":true", "\"versions\":[]");

			var unavailable = request(port, "GET", "/api/v1/training-projects/" + unavailableId + "/versions", null);
			assertThat(unavailable.statusCode()).isEqualTo(200);
			assertThat(unavailable.body()).contains("\"registryAvailable\":false", "PROJECT_REGISTRY_UNAVAILABLE");

			var assessment = request(port, "GET",
					"/api/v1/training-projects/" + availableId + "/versions/sha256:" + "9".repeat(64), null);
			assertThat(assessment.statusCode()).isEqualTo(200);
			assertThat(assessment.body()).contains("\"runnable\":false", "PROJECT_VERSION_MISSING", "\"assessedAt\"");
		}
	}

	@Test
	void rebindingRetainsFailureRefreshesLateRunReferencesAndPromotesOnlyAfterEveryDigestIsCopied() throws Exception {
		try (var database = PostgreSqlFixture.freshDatabase(); var application = start(database)) {
			int port = ((WebServerApplicationContext) application).getWebServer().getPort();
			var references = application
				.getBean(TrainingProjectIntegrationTestConfiguration.MutableArtifactReferences.class);
			var registry = application.getBean(TrainingProjectIntegrationTestConfiguration.DeterministicRegistry.class);
			String manifest = "sha256:" + "a".repeat(64);
			String lateImage = "sha256:" + "b".repeat(64);
			references.add(new ReferencedProjectArtifact(ReferencedProjectArtifact.Kind.VERSION_MANIFEST, manifest));
			String projectId = create(port, "Rebinding", "original");

			var started = request(port, "POST", "/api/v1/training-projects/" + projectId + "/registry-rebindings",
					"""
							{"expectedRevision":1,"candidate":{"repository":"ghcr.io/example/replacement","accessMode":"public"}}
							""");
			assertThat(started.statusCode()).as(started.body()).isEqualTo(201);
			assertThat(started.body()).contains("\"state\":\"failed\"", "REGISTRY_REBINDING_ARTIFACT_MISSING",
					manifest);
			String operationId = jsonString(started.body(), "id");
			var beforePromotion = request(port, "GET", "/api/v1/training-projects/" + projectId, null);
			assertThat(beforePromotion.body()).contains("\"repository\":\"ghcr.io/example/original\"",
					"\"state\":\"candidate\"");

			registry.copy(manifest);
			references.add(new ReferencedProjectArtifact(ReferencedProjectArtifact.Kind.IMAGE, lateImage));
			var lateReference = request(port, "POST",
					"/api/v1/training-projects/" + projectId + "/registry-rebindings/" + operationId + "/retry",
					"{\"expectedRevision\":2}");
			assertThat(lateReference.statusCode()).as(lateReference.body()).isEqualTo(200);
			assertThat(lateReference.body()).contains("\"state\":\"failed\"", lateImage, "\"attempts\":2");

			registry.copy(lateImage);
			var promoted = request(port, "POST",
					"/api/v1/training-projects/" + projectId + "/registry-rebindings/" + operationId + "/retry",
					"{\"expectedRevision\":2}");
			assertThat(promoted.statusCode()).as(promoted.body()).isEqualTo(200);
			assertThat(promoted.body()).contains("\"state\":\"promoted\"", "\"attempts\":3", "\"completedAt\"");
			var afterPromotion = request(port, "GET", "/api/v1/training-projects/" + projectId, null);
			assertThat(afterPromotion.body()).contains("\"revision\":3",
					"\"repository\":\"ghcr.io/example/replacement\"", "\"state\":\"retired\"");
		}
	}

	private static String create(int port, String name, String repository) throws Exception {
		var response = request(port, "POST", "/api/v1/training-projects", """
				{"displayName":"%s","registry":{"repository":"ghcr.io/example/%s","accessMode":"public"}}
				""".formatted(name, repository));
		assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
		return response.body().replaceFirst("(?s).*?\"id\":\"([^\"]+)\".*", "$1");
	}

	private static String jsonString(String body, String field) {
		return body.replaceFirst("(?s).*?\"" + field + "\":\"([^\"]+)\".*", "$1");
	}

	private static org.springframework.context.ConfigurableApplicationContext start(
			PostgreSqlFixture.Database database) {
		var arguments = new ArrayList<>(database.backendArguments());
		arguments.add("--server.port=0");
		arguments.add("--skywright.deployment.environment=test");
		arguments.add("--spring.profiles.active=training-project-integration");
		return new SpringApplicationBuilder(SkywrightBackendApplication.class,
				TrainingProjectIntegrationTestConfiguration.class)
			.run(arguments.toArray(String[]::new));
	}

	private static HttpResponse<String> request(int port, String method, String path, String body) throws Exception {
		var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
		if (body == null) {
			builder.method(method, HttpRequest.BodyPublishers.noBody());
		}
		else {
			builder.header("Content-Type", "application/json")
				.method(method, HttpRequest.BodyPublishers.ofString(body));
		}
		return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

}
