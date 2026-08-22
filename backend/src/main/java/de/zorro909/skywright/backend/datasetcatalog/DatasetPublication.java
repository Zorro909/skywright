package de.zorro909.skywright.backend.datasetcatalog;

import java.time.Instant;
import java.util.UUID;
import java.util.List;

public record DatasetPublication(UUID datasetId, UUID definitionId, String versionLabel, String contentFingerprint,
		String manifestIdentity, UUID copyId, UUID targetStorageId, String location, long verifiedBytes,
		Instant verifiedAt, List<DatasetManifestEntry> manifestEntries) {

	public DatasetPublication(UUID datasetId, UUID definitionId, String versionLabel, String contentFingerprint,
			String manifestIdentity, UUID copyId, UUID targetStorageId, String location, long verifiedBytes,
			Instant verifiedAt) {
		this(datasetId, definitionId, versionLabel, contentFingerprint, manifestIdentity, copyId, targetStorageId,
				location, verifiedBytes, verifiedAt, List.of());
	}

	public DatasetPublication {
		manifestEntries = List.copyOf(manifestEntries);
	}
}
