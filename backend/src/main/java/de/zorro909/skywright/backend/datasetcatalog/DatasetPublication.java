package de.zorro909.skywright.backend.datasetcatalog;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DatasetPublication(UUID datasetId, UUID definitionId, String versionLabel, String contentFingerprint,
		String manifestIdentity, UUID copyId, UUID targetStorageId, String location, long verifiedBytes,
		Instant verifiedAt, List<DatasetManifestEntry> manifestEntries) {

	public DatasetPublication {
		manifestEntries = List.copyOf(manifestEntries);
	}
}
