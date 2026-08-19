package de.zorro909.skywright.backend.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import de.zorro909.skywright.backend.targetstorage.BindingReadiness;
import de.zorro909.skywright.backend.targetstorage.TargetStorageBindingReadiness;
import de.zorro909.skywright.backend.targetstorage.TargetStorageCredentialAccess;
import de.zorro909.skywright.backend.targetstorage.TargetStorageRegistry;
import de.zorro909.skywright.backend.targetstorage.TargetStorageRole;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("real-service")
final class TargetStorageControlPlaneIT {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void springControlPlaneQualifiesPromotesDefaultsResolvesAndProtectsARealResource() throws Exception {
		var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test-key", "test-secret"));
		try (SeaweedFsFixture service = SeaweedFsFixture.start();
				S3AsyncClient administrator = S3AsyncClient.builder()
					.httpClientBuilder(NettyNioAsyncHttpClient.builder())
					.endpointOverride(service.endpoint())
					.region(Region.US_EAST_1)
					.credentialsProvider(credentials)
					.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
					.build()) {
			service.awaitReady(administrator);
			String bucket = "control-plane-" + UUID.randomUUID();
			administrator.createBucket(CreateBucketRequest.builder().bucket(bucket).build()).join();
			TargetStorageBindingReadiness readiness = (bindingId, bindingRevision,
					consumingRole) -> BindingReadiness.READY;
			TargetStorageCredentialAccess credentialAccess = (bindingId, bindingRevision, consumingRole) -> Optional
				.of(credentials);

			try (var backend = BackendFixture.start(Map.of("acceptanceTargetStorageBindingReadiness", readiness,
					"acceptanceTargetStorageCredentialAccess", credentialAccess))) {
				var createdResponse = backend.request("POST", "/api/v1/target-storages",
						registration(service.endpoint().toString(), bucket));
				assertThat(createdResponse.statusCode()).isEqualTo(201);
				assertThat(createdResponse.body()).doesNotContain("test-secret", "test-key");
				JsonNode created = JSON.readTree(createdResponse.body());
				String storageId = created.get("id").asText();
				assertThat(created.get("activeRevision").asLong()).isEqualTo(1L);
				assertThat(created.get("candidateRevision").isNull()).isTrue();
				assertThat(created.get("assessments").get(0).get("availability").asText()).isEqualTo("available");
				assertThat(created.get("assessments").get(0).get("capabilities")).hasSize(19);

				var activatedResponse = backend.request("PUT", "/api/v1/target-storages/" + storageId + "/activation",
						"{\"expectedRegistrationRevision\":" + created.get("registrationRevision").asLong()
								+ ",\"activated\":true}");
				assertThat(activatedResponse.statusCode()).isEqualTo(200);
				assertThat(JSON.readTree(activatedResponse.body()).get("eligible").asBoolean()).isTrue();

				var defaultsResponse = backend.request("PUT", "/api/v1/target-storage-defaults/local-single-gpu",
						"{\"executionStorageId\":\"" + storageId
								+ "\",\"repatriationEnabled\":true,\"repatriationStorageId\":\"" + storageId + "\"}");
				assertThat(defaultsResponse.statusCode()).as(defaultsResponse.body()).isEqualTo(200);
				assertThat(defaultsResponse.body()).contains("local-single-gpu", storageId);

				JsonNode activated = JSON.readTree(activatedResponse.body());
				var revisedResponse = backend.request("POST", "/api/v1/target-storages/" + storageId + "/revisions",
						stageRevision(activated.get("registrationRevision").asLong(), service.endpoint().toString()));
				assertThat(revisedResponse.statusCode()).isEqualTo(200);
				JsonNode revised = JSON.readTree(revisedResponse.body());
				assertThat(revised.get("activeRevision").asLong()).isEqualTo(2L);
				assertThat(revised.get("candidateRevision").isNull()).isTrue();
				assertThat(revised.get("assessments")).hasSize(2);

				var resolved = backend.bean(TargetStorageRegistry.class)
					.resolveRunStore(UUID.fromString(storageId), TargetStorageRole.BACKEND, "project", "run");
				assertThat(resolved.storageId()).isEqualTo(storageId);
				assertThat(resolved.endpoint()).isEqualTo(service.endpoint());
				assertThat(resolved.credentials()).isSameAs(credentials);

				var deleteResponse = backend.request("DELETE", "/api/v1/target-storages/" + storageId, null);
				assertThat(deleteResponse.statusCode()).isEqualTo(409);
				assertThat(deleteResponse.body()).contains("SKYWRIGHT_TARGET_STORAGE_REFERENCED");
			}

			assertThat(administrator.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build())
				.join()
				.contents()).isEmpty();
			assertThat(administrator.listMultipartUploads(ListMultipartUploadsRequest.builder().bucket(bucket).build())
				.join()
				.uploads()).isEmpty();
		}
	}

	private static String registration(String endpoint, String bucket) {
		return """
				{
				  "name": "System outputs",
				  "purpose": "run-output",
				  "bucket": "%s",
				  "configuration": {
				    "endpoint": "%s",
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
				""".formatted(bucket, endpoint);
	}

	private static String stageRevision(long registrationRevision, String endpoint) {
		return """
				{
				  "expectedRegistrationRevision": %d,
				  "configuration": {
				    "endpoint": "%s",
				    "region": "us-east-1",
				    "pathStyleAccess": true,
				    "compatibilityOptions": {"chunkedEncoding":"false"}
				  }
				}
				""".formatted(registrationRevision, endpoint);
	}

}
