package de.zorro909.skywright.backend.acceptance;

import static org.assertj.core.api.Assertions.*;
import de.zorro909.skywright.backend.datasetcatalog.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.*;
import tools.jackson.databind.json.JsonMapper;

@Tag("real-service")
class DatasetChecksumApiIT {

	@Test
	void qualifiedStoragePromotesAndRefreshesSinglePartMultipartAndHeaderlessObjects() throws Exception {
		try (var storage = SeaweedFsFixture.start(); var admin = DatasetCatalogApiIT.administrator(storage)) {
			storage.awaitReady(admin);
			String bucket = "dataset-checksums-" + UUID.randomUUID();
			admin.createBucket(b -> b.bucket(bucket)).join();
			try (var backend = BackendFixture.startWithTargetStorageIntegration()) {
				var response = backend.post("/api/v1/target-storages",
						DatasetCatalogApiIT.registration(storage.endpoint(), bucket));
				assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
				UUID storageId = UUID
					.fromString(JsonMapper.builder().build().readTree(response.body()).path("id").asText());
				var activation = backend.put("/api/v1/target-storages/" + storageId + "/activation",
						"{\"expectedRegistrationRevision\":2,\"activated\":true}");
				assertThat(activation.statusCode()).as(activation.body()).isEqualTo(200);
				var catalog = backend.bean(DatasetCatalog.class);
				for (String mode : List.of("single", "multipart", "headerless")) {
					byte[] bytes = new byte[mode.equals("multipart") ? 5 * 1024 * 1024 + 37 : 4096];
					new Random(223).nextBytes(bytes);
					String checksum = checksum(bytes);
					String source = "datasets/" + mode + "/source";
					String replica = "datasets/" + mode + "/replica";
					upload(admin, bucket, source + "/shard.bin", bytes, mode);
					upload(admin, bucket, replica + "/shard.bin", bytes, mode);
					var head = admin
						.headObject(
								b -> b.bucket(bucket).key(replica + "/shard.bin").checksumMode(ChecksumMode.ENABLED))
						.join();
					System.out.println("Dataset checksum qualification: " + mode + ", type="
							+ head.checksumTypeAsString() + ", sha256=" + head.checksumSHA256());
					if (mode.equals("headerless"))
						assertThat(head.checksumSHA256()).isNull();
					UUID definition = UUID.randomUUID();
					UUID authority = UUID.randomUUID();
					UUID candidate = UUID.randomUUID();
					catalog
						.publish(new DatasetPublication(UUID.randomUUID(), definition, mode, "mosaicml-streaming-mds@2",
								"sha256:content", "sha256:manifest", authority, storageId, source, bytes.length,
								Instant.now(), List.of(new DatasetManifestEntry("shard.bin", bytes.length, checksum))));
					catalog.addReplica(definition,
							new DatasetReplicaPublication(candidate, storageId, replica, bytes.length, Instant.now()),
							1);
					var promotionResponse = backend.post(
							"/api/v1/dataset-catalog/" + definition + "/copies/" + candidate + "/promotion",
							"{\"expectedRevision\":2}");
					assertThat(promotionResponse.statusCode()).as(promotionResponse.body()).isEqualTo(202);
					UUID promotionId = UUID.fromString(
							JsonMapper.builder().build().readTree(promotionResponse.body()).path("id").asText());
					if (mode.equals("single")) {
						backend.restart();
						catalog = backend.bean(DatasetCatalog.class);
					}
					awaitOperation(catalog, definition, promotionId);
					assertThat(catalog.getOperation(definition, promotionId).progress())
						.isEqualTo(DatasetCopyOperationProgress.COMPLETED);
					var operation = catalog.startRefresh(definition, candidate, 1, catalog.get(definition).revision());
					long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(45);
					while (catalog.getOperation(definition, operation.id()).active() && System.nanoTime() < deadline)
						Thread.sleep(100);
					assertThat(catalog.getOperation(definition, operation.id()).progress()).as(mode)
						.isEqualTo(DatasetCopyOperationProgress.COMPLETED);
					var refreshed = catalog.get(definition)
						.copies()
						.stream()
						.filter(c -> c.id().equals(candidate))
						.findFirst()
						.orElseThrow();
					assertThat(refreshed.currentGeneration().number()).isEqualTo(2);
					assertThat(refreshed.currentGeneration().verifiedBytes()).isEqualTo(bytes.length);
					// Metadata is merely a claim: matching metadata cannot hide wrong
					// bytes or size.
					for (byte[] damaged : List.of(new byte[bytes.length], new byte[bytes.length - 1])) {
						admin
							.putObject(
									b -> b.bucket(bucket)
										.key(source + "/shard.bin")
										.metadata(Map.of("skywright-sha256", checksum)),
									AsyncRequestBody.fromBytes(damaged))
							.join();
						long revision = catalog.get(definition).revision();
						var failed = catalog.promote(definition, authority, revision);
						awaitOperation(catalog, definition, failed.id());
						assertThat(catalog.getOperation(definition, failed.id()).failureCode())
							.isEqualTo("DATASET_COPY_MANIFEST_MISMATCH");
						assertThat(catalog.get(definition).copies())
							.filteredOn(copy -> copy.role() == DatasetCopyRole.AUTHORITY)
							.singleElement()
							.extracting(DatasetCopyView::id)
							.isEqualTo(candidate);
					}
				}
				var jdbc = new org.springframework.jdbc.core.JdbcTemplate(backend.bean(javax.sql.DataSource.class));
				assertThat(jdbc.queryForObject(
						"select count(*) from skywright.dataset_copy_worker_projection where worker_pid is not null and worker_pid <> ? and consumer_role = 'transfer-worker'",
						Long.class, ProcessHandle.current().pid()))
					.isGreaterThan(0L);
				assertThat(jdbc.queryForObject(
						"select count(*) from skywright.dataset_copy_worker_projection where released_at is null",
						Long.class))
					.isZero();
			}
		}
	}

	static void awaitOperation(DatasetCatalog catalog, UUID definition, UUID operation) throws Exception {
		long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
		while (catalog.getOperation(definition, operation).active() && System.nanoTime() < deadline)
			Thread.sleep(100);
		assertThat(catalog.getOperation(definition, operation).active()).isFalse();
	}

	private static void upload(S3AsyncClient client, String bucket, String key, byte[] bytes, String mode)
			throws Exception {
		if (!mode.equals("multipart")) {
			var request = PutObjectRequest.builder().bucket(bucket).key(key);
			if (mode.equals("single"))
				request.checksumSHA256(checksum(bytes));
			client.putObject(request.build(), AsyncRequestBody.fromBytes(bytes)).join();
			return;
		}
		String upload = client
			.createMultipartUpload(b -> b.bucket(bucket).key(key).checksumAlgorithm(ChecksumAlgorithm.SHA256))
			.join()
			.uploadId();
		var parts = new ArrayList<CompletedPart>();
		int partSize = 5 * 1024 * 1024;
		for (int offset = 0; offset < bytes.length; offset += partSize) {
			byte[] part = Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + partSize));
			int number = parts.size() + 1;
			String digest = checksum(part);
			var written = client
				.uploadPart(b -> b.bucket(bucket).key(key).uploadId(upload).partNumber(number).checksumSHA256(digest),
						AsyncRequestBody.fromBytes(part))
				.join();
			parts.add(CompletedPart.builder().partNumber(number).eTag(written.eTag()).checksumSHA256(digest).build());
		}
		client
			.completeMultipartUpload(b -> b.bucket(bucket)
				.key(key)
				.uploadId(upload)
				.multipartUpload(CompletedMultipartUpload.builder().parts(parts).build()))
			.join();
	}

	private static String checksum(byte[] bytes) throws Exception {
		return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
	}

}
