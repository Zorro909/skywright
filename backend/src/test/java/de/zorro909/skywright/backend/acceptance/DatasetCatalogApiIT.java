package de.zorro909.skywright.backend.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import de.zorro909.skywright.backend.datasetcatalog.DatasetCatalog;
import de.zorro909.skywright.backend.datasetcatalog.DatasetCacheOwnerType;
import de.zorro909.skywright.backend.datasetcatalog.DatasetCacheReport;
import de.zorro909.skywright.backend.datasetcatalog.DatasetManifestEntry;
import de.zorro909.skywright.backend.datasetcatalog.DatasetPublication;
import de.zorro909.skywright.backend.datasetcatalog.DatasetReplicaPublication;
import java.net.URI;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Tag("real-service")
final class DatasetCatalogApiIT {

	@Test
	void publishedCatalogFactsSurviveJpaAndAreQueryableThroughTheGeneratedApi() throws Exception {
		try (var objectStorage = SeaweedFsFixture.start(); var administrator = administrator(objectStorage)) {
			objectStorage.awaitReady(administrator);
			String bucket = "dataset-catalog-api-" + UUID.randomUUID();
			administrator.createBucket(CreateBucketRequest.builder().bucket(bucket).build()).join();
			try (var backend = BackendFixture.startWithTargetStorageIntegration()) {
				var storage = backend.post("/api/v1/target-storages", registration(objectStorage.endpoint(), bucket));
				assertThat(storage.statusCode()).as(storage.body()).isEqualTo(201);
				UUID storageId = UUID.fromString(storage.body().replaceFirst("(?s).*?\"id\":\"([^\"]+)\".*", "$1"));
				var activated = backend.put("/api/v1/target-storages/" + storageId + "/activation",
						"{\"expectedRegistrationRevision\":2,\"activated\":true}");
				assertThat(activated.statusCode()).as(activated.body()).isEqualTo(200);
				UUID definitionId = UUID.randomUUID();
				UUID datasetId = UUID.randomUUID();
				UUID runRecordId = UUID.randomUUID();
				UUID copyId = UUID.randomUUID();
				byte[] shard = new byte[4096];
				String checksum = Base64.getEncoder()
					.encodeToString(MessageDigest.getInstance("SHA-256").digest(shard));
				administrator
					.putObject(PutObjectRequest.builder()
						.bucket(bucket)
						.key("datasets/release-1/shard.bin")
						.checksumSHA256(checksum)
						.build(), AsyncRequestBody.fromBytes(shard))
					.join();
				backend.bean(DatasetCatalog.class)
					.publish(new DatasetPublication(datasetId, definitionId, "release-1", "sha256:content",
							"sha256:manifest", copyId, storageId, "datasets/release-1", 4096,
							Instant.parse("2026-08-22T10:00:00Z"),
							List.of(new DatasetManifestEntry("shard.bin", 4096, checksum))));
				DatasetCatalog catalog = backend.bean(DatasetCatalog.class);
				Instant now = Instant.parse("2026-08-22T10:00:00Z");
				UUID replicaId = UUID.randomUUID();
				catalog.addReplica(definitionId,
						new DatasetReplicaPublication(replicaId, storageId, "datasets/release-1", 4096, now), 1);
				catalog.promote(definitionId, replicaId, 2);
				catalog.reportCache(definitionId, new DatasetCacheReport(UUID.randomUUID(), DatasetCacheOwnerType.HOST,
						"trainer-01", 512, now, now), 3);
				catalog.acquireLease(definitionId, copyId, 1, 4, runRecordId);
				catalog.startRefresh(definitionId, copyId, 1, 5);

				var record = backend.get("/api/v1/dataset-catalog/" + definitionId);
				var page = backend.get("/api/v1/dataset-catalog?limit=1&datasetId=" + datasetId + "&definitionId="
						+ definitionId + "&targetStorageId=" + storageId
						+ "&role=authority&eligible=false&operationState=waiting-for-leases&cacheOwnerType=host"
						+ "&cacheOwnerId=trainer-01&runRecordId=" + runRecordId);

				assertThat(record.statusCode()).as(record.body()).isEqualTo(200);
				assertThat(record.body()).contains(definitionId.toString(), "release-1", "datasets/release-1",
						"\"role\":\"authority\"", "\"activeLeaseCount\":1");
				assertThat(page.statusCode()).as(page.body()).isEqualTo(200);
				assertThat(page.body()).contains(definitionId.toString(), "\"nextCursor\":null");
			}
		}
	}

	private static S3AsyncClient administrator(SeaweedFsFixture storage) {
		return S3AsyncClient.builder()
			.httpClientBuilder(NettyNioAsyncHttpClient.builder())
			.endpointOverride(storage.endpoint())
			.region(Region.US_EAST_1)
			.credentialsProvider(
					StaticCredentialsProvider.create(AwsBasicCredentials.create("test-key", "test-secret")))
			.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
			.build();
	}

	private static String registration(URI endpoint, String bucket) {
		return """
				{
				  "name": "Dataset authority",
				  "purpose": "dataset",
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
				    {"role":"transfer-worker","bindingId":"00000000-0000-0000-0000-000000000003","bindingRevision":1}
				  ]
				}
				""".formatted(bucket, endpoint);
	}

}
