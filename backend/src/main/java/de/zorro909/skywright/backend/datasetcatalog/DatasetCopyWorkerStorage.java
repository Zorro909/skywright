package de.zorro909.skywright.backend.datasetcatalog;

import de.zorro909.skywright.backend.runstore.ResolvedTargetStorage;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import de.zorro909.skywright.backend.worker.TransferObjects;
import java.io.IOException;
import java.util.Base64;
import java.util.HexFormat;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;

final class DatasetCopyWorkerStorage implements DatasetCopyStorage {

	private static final int MAX_DELETE_OBJECTS = 1_000;

	private final ResolvedTargetStorage target;

	DatasetCopyWorkerStorage(ResolvedTargetStorage target) {
		this.target = target;
	}

	@Override
	public void verify(DatasetDefinitionView definition, List<DatasetManifestEntry> manifest, DatasetCopyView copy) {
		this.requireManifest(manifest);
		ResolvedTargetStorage target = this.target;
		try (S3AsyncClient client = client(target)) {
			for (DatasetManifestEntry entry : manifest) {

				try {
					TransferObjects.verify(client, target.bucket(),
							key(copy.currentGeneration().location(), entry.objectKey()), entry.byteCount(),
							HexFormat.of().formatHex(Base64.getDecoder().decode(entry.checksumSha256())));
				}
				catch (TransferObjects.IntegrityMismatch mismatch) {
					throw new DatasetCatalogConflictException("DATASET_COPY_MANIFEST_MISMATCH",
							"Dataset Copy does not match the Dataset Definition integrity manifest");
				}
				catch (IOException failure) {
					throw unavailable(copy.targetStorageId());
				}
			}
		}
		catch (DatasetCatalogException failure) {
			throw failure;
		}
		catch (RuntimeException failure) {
			throw unavailable(copy.targetStorageId());
		}
	}

	@Override
	public VerifiedDatasetReplacement stageReplacement(DatasetDefinitionView definition,
			List<DatasetManifestEntry> manifest, DatasetCopyView copy, UUID operationId) {
		this.requireManifest(manifest);
		ResolvedTargetStorage target = this.target;
		String stagedLocation = copy.currentGeneration().location() + ".refresh-" + operationId;
		try (S3AsyncClient client = client(target)) {
			for (DatasetManifestEntry entry : manifest) {
				String sourceKey = key(copy.currentGeneration().location(), entry.objectKey());
				client
					.copyObject(CopyObjectRequest.builder()
						.copySource(URLEncoder.encode(target.bucket() + "/" + sourceKey, StandardCharsets.UTF_8)
							.replace("+", "%20"))
						.destinationBucket(target.bucket())
						.destinationKey(key(stagedLocation, entry.objectKey()))
						.build())
					.join();
			}
		}
		catch (RuntimeException failure) {
			throw unavailable(copy.targetStorageId());
		}
		return this.verifyReplacement(definition, manifest, copy, operationId);
	}

	@Override
	public VerifiedDatasetReplacement verifyReplacement(DatasetDefinitionView definition,
			List<DatasetManifestEntry> manifest, DatasetCopyView copy, UUID operationId) {
		String stagedLocation = copy.currentGeneration().location() + ".refresh-" + operationId;
		DatasetCopyGenerationView generation = copy.currentGeneration();
		var stagedGeneration = new DatasetCopyGenerationView(generation.number(), stagedLocation,
				generation.manifestIdentity(), generation.contentFingerprint(), generation.verifiedBytes(),
				generation.createdAt(), generation.verifiedAt(), false, DatasetCopyAvailability.AVAILABLE);
		var stagedCopy = new DatasetCopyView(copy.id(), copy.targetStorageId(), copy.role(), copy.revision(),
				stagedGeneration, List.of(stagedGeneration), copy.activeLeaseCount());
		this.verify(definition, manifest, stagedCopy);
		return new VerifiedDatasetReplacement(stagedLocation, generation.verifiedBytes(), definition.manifestIdentity(),
				definition.contentFingerprint(), Instant.now());
	}

	@Override
	public void deleteAndVerify(List<DatasetManifestEntry> manifest, DatasetCopyView copy, long generation) {
		this.requireManifest(manifest);
		DatasetCopyGenerationView selected = copy.generationHistory()
			.stream()
			.filter(value -> value.number() == generation)
			.findFirst()
			.orElseThrow();
		ResolvedTargetStorage target = this.target;
		try (S3AsyncClient client = client(target)) {
			List<ObjectIdentifier> objects = manifest.stream()
				.map(entry -> ObjectIdentifier.builder().key(key(selected.location(), entry.objectKey())).build())
				.toList();
			for (int start = 0; start < objects.size(); start += MAX_DELETE_OBJECTS) {
				List<ObjectIdentifier> batch = objects.subList(start,
						Math.min(start + MAX_DELETE_OBJECTS, objects.size()));
				client
					.deleteObjects(DeleteObjectsRequest.builder()
						.bucket(target.bucket())
						.delete(Delete.builder().objects(batch).build())
						.build())
					.join();
				for (ObjectIdentifier object : batch) {
					try {
						client.headObject(HeadObjectRequest.builder().bucket(target.bucket()).key(object.key()).build())
							.join();
						throw new DatasetStorageUnavailableException(copy.targetStorageId().toString(),
								"Dataset object absence could not be verified");
					}
					catch (RuntimeException failure) {
						if (!isMissing(failure)) {
							throw failure;
						}
					}
				}
			}
		}
		catch (DatasetCatalogException failure) {
			throw failure;
		}
		catch (RuntimeException failure) {
			throw unavailable(copy.targetStorageId());
		}
	}

	private void requireManifest(List<DatasetManifestEntry> manifest) {
		if (manifest.isEmpty()) {
			throw new DatasetCatalogConflictException("DATASET_MANIFEST_UNAVAILABLE",
					"The complete Dataset integrity manifest is unavailable");
		}
	}

	private static S3AsyncClient client(ResolvedTargetStorage target) {
		return TransferObjects.client(target.endpoint(), target.region().id(), target.credentials(),
				target.pathStyleAccess(), "enabled".equals(target.compatibilityOptions().get("chunkedEncoding")),
				TransferObjects.checksumCalculation(target.compatibilityOptions().get("checksumCalculation")),
				ClientOverrideConfiguration.builder()
					.apiCallTimeout(java.time.Duration.ofSeconds(30))
					.apiCallAttemptTimeout(java.time.Duration.ofSeconds(15))
					.build(),
				java.time.Duration.ofSeconds(30));
	}

	private static String key(String location, String objectKey) {
		return location.endsWith("/") ? location + objectKey : location + "/" + objectKey;
	}

	private static boolean isMissing(RuntimeException failure) {
		Throwable cause = failure;
		while (cause != null) {
			if (cause instanceof NoSuchKeyException
					|| cause instanceof software.amazon.awssdk.services.s3.model.S3Exception s3
							&& s3.statusCode() == 404) {
				return true;
			}
			cause = cause.getCause();
		}
		return false;
	}

	private static DatasetStorageUnavailableException unavailable(UUID storageId) {
		return new DatasetStorageUnavailableException(storageId.toString(),
				"Dataset storage operation failed transiently");
	}

}
