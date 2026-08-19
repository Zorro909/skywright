package de.zorro909.skywright.backend.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import de.zorro909.skywright.backend.SkywrightBackendApplication;
import de.zorro909.skywright.backend.targetstorage.TargetStorageIntegrationTestConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

@Tag("real-service")
final class TargetStorageApiIT {

	private static final StaticCredentialsProvider CREDENTIALS = StaticCredentialsProvider
		.create(AwsBasicCredentials.create("test-key", "test-secret"));

	@Test
	void realControlPlaneQualifiesActivatesAndDefaultsARegisteredDestination() throws Exception {
		try (var database = PostgreSqlFixture.freshDatabase();
				var storage = SeaweedFsFixture.start();
				var administrator = administrator(storage)) {
			storage.awaitReady(administrator);
			String bucket = "target-storage-api-" + UUID.randomUUID();
			administrator.createBucket(CreateBucketRequest.builder().bucket(bucket).build()).join();

			try (var application = start(database)) {
				int port = ((WebServerApplicationContext) application).getWebServer().getPort();
				var secretEndpoint = request(port, "POST", "/api/v1/target-storages",
						registration(URI.create("http://operator:do-not-return@storage.example?token=hidden"),
								"secret-endpoint", "run-output"));
				assertThat(secretEndpoint.statusCode()).isEqualTo(422);
				assertThat(secretEndpoint.body()).contains("SKYWRIGHT_TARGET_STORAGE_CONFIGURATION_INVALID")
					.doesNotContain("do-not-return", "token=hidden");

				var created = request(port, "POST", "/api/v1/target-storages",
						registration(storage.endpoint(), bucket, "run-output"));
				assertThat(created.statusCode()).isEqualTo(201);
				assertThat(created.body()).contains("\"availability\":\"available\"", "\"activeRevision\":1",
						"\"eligible\":false");
				String storageId = jsonString(created.body(), "id");

				var activated = request(port, "PUT", "/api/v1/target-storages/" + storageId + "/activation",
						"{\"expectedRegistrationRevision\":2,\"activated\":true}");
				assertThat(activated.statusCode()).isEqualTo(200);
				assertThat(activated.body()).contains("\"eligible\":true");

				var defaults = request(port, "PUT", "/api/v1/target-storage-defaults/local-single-gpu",
						defaults(storageId));
				assertThat(defaults.statusCode()).as(defaults.body()).isEqualTo(200);
				assertThat(defaults.body()).contains(storageId, "local-single-gpu");

				var revision = request(port, "POST", "/api/v1/target-storages/" + storageId + "/revisions",
						revision(storage.endpoint(), 3));
				assertThat(revision.statusCode()).isEqualTo(200);
				assertThat(revision.body()).contains("\"activeRevision\":2", "\"candidateRevision\":null",
						"\"eligible\":true");

				var failedCandidate = request(port, "POST", "/api/v1/target-storages/" + storageId + "/revisions",
						revision(URI.create("http://127.0.0.1:1"), 5));
				assertThat(failedCandidate.statusCode()).isEqualTo(200);
				assertThat(failedCandidate.body()).contains("\"activeRevision\":2", "\"candidateRevision\":3",
						"\"eligible\":true", "transient-storage-outage", "127.0.0.1:1");

				var conflict = request(port, "POST", "/api/v1/target-storages",
						registration(storage.endpoint(), bucket, "dataset"));
				assertThat(conflict.statusCode()).isEqualTo(409);
				assertThat(conflict.body()).contains("SKYWRIGHT_TARGET_STORAGE_PURPOSE_CONFLICT");

				var deactivated = request(port, "PUT", "/api/v1/target-storages/" + storageId + "/activation",
						"{\"expectedRegistrationRevision\":7,\"activated\":false}");
				assertThat(deactivated.statusCode()).isEqualTo(200);
				assertThat(deactivated.body()).contains("\"eligible\":false");

				var deletion = request(port, "DELETE", "/api/v1/target-storages/" + storageId, null);
				assertThat(deletion.statusCode()).isEqualTo(409);
				assertThat(deletion.body()).contains("SKYWRIGHT_TARGET_STORAGE_REFERENCED");
			}
		}
	}

	private static S3AsyncClient administrator(SeaweedFsFixture storage) {
		return S3AsyncClient.builder()
			.httpClientBuilder(NettyNioAsyncHttpClient.builder())
			.endpointOverride(storage.endpoint())
			.region(Region.US_EAST_1)
			.credentialsProvider(CREDENTIALS)
			.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
			.build();
	}

	private static org.springframework.context.ConfigurableApplicationContext start(
			PostgreSqlFixture.Database database) {
		var arguments = new ArrayList<>(database.backendArguments());
		arguments.add("--server.port=0");
		arguments.add("--skywright.deployment.environment=test");
		arguments.add("--spring.profiles.active=target-storage-integration");
		return new SpringApplicationBuilder(SkywrightBackendApplication.class,
				TargetStorageIntegrationTestConfiguration.class)
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

	private static String registration(URI endpoint, String bucket, String purpose) {
		return """
				{
				  "name": "API integration storage",
				  "purpose": "%s",
				  "bucket": "%s",
				  "configuration": {
				    "endpoint": "%s",
				    "region": "us-east-1",
				    "pathStyleAccess": true,
				    "compatibilityOptions": {"chunkedEncoding":"disabled"}
				  },
				  "bindings": [
				    {"role":"training-process","bindingId":"00000000-0000-0000-0000-000000000001","bindingRevision":1},
				    {"role":"backend","bindingId":"00000000-0000-0000-0000-000000000002","bindingRevision":1},
				    {"role":"transfer-worker","bindingId":"00000000-0000-0000-0000-000000000003","bindingRevision":1},
				    {"role":"metric-view","bindingId":"00000000-0000-0000-0000-000000000004","bindingRevision":1}
				  ]
				}
				""".formatted(purpose, bucket, endpoint);
	}

	private static String revision(URI endpoint, long expectedRegistrationRevision) {
		return """
				{
				  "expectedRegistrationRevision": %d,
				  "configuration": {
				    "endpoint": "%s",
				    "region": "us-east-1",
				    "pathStyleAccess": true,
				    "compatibilityOptions": {"chunkedEncoding":"disabled"}
				  }
				}
				""".formatted(expectedRegistrationRevision, endpoint);
	}

	private static String defaults(String storageId) {
		return """
				{"executionStorageId":"%s","repatriationEnabled":true,"repatriationStorageId":"%s"}
				""".formatted(storageId, storageId);
	}

	private static String jsonString(String body, String field) {
		return body.replaceFirst("(?s).*?\\\"" + field + "\\\":\\\"([^\\\"]+)\\\".*", "$1");
	}

}
