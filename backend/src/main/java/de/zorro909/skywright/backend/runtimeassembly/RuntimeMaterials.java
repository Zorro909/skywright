package de.zorro909.skywright.backend.runtimeassembly;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

/**
 * Non-secret pinned bytes and execution-location facts delivered beside the unchanged Run
 * Definition.
 */
public record RuntimeMaterials(int materialsVersion, UUID runId, String image, String configurationContract,
		String metricContract, Dataset dataset, DatasetLocation datasetLocation, SourceCheckpoint sourceCheckpoint) {

	public RuntimeMaterials {
		if (materialsVersion != 1)
			throw new IllegalArgumentException("Unsupported runtime materials version");
		java.util.Objects.requireNonNull(runId);
		java.util.Objects.requireNonNull(dataset);
		java.util.Objects.requireNonNull(datasetLocation);
	}
	public record Dataset(String datasetIdentity, String version, String contentFingerprint, String manifestIdentity,
			List<DatasetObject> objects) {
		public Dataset {
			objects = List.copyOf(objects);
		}
	}

	public record DatasetObject(@JsonProperty("object_key") String objectKey,
			@JsonProperty("byte_count") long byteCount, String sha256) {
	}

	public record DatasetLocation(@JsonProperty("storage_id") String storageId, String endpoint, String bucket,
			String region, String prefix, @JsonProperty("copy_id") String copyId, long generation,
			@JsonProperty("lease_id") String leaseId, @JsonProperty("path_style") boolean pathStyle,
			@JsonProperty("checksum_calculation") String checksumCalculation) {
	}

	public record SourceStorage(String storageId, long registrationRevision, long configurationRevision,
			String endpoint, String bucket, String region, String addressingMode,
			java.util.Map<String, String> compatibilityOptions) {
		public SourceStorage {
			compatibilityOptions = java.util.Map.copyOf(compatibilityOptions);
		}
	}

	public record SourceCheckpoint(UUID runId, String reference, SourceStorage storage,
			@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) UUID ownedByRunId) {
		public SourceCheckpoint(UUID runId, String reference, SourceStorage storage) {
			this(runId, reference, storage, null);
		}
	}
}
