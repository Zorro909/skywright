package de.zorro909.skywright.backend.datasetpublication;

import de.zorro909.skywright.backend.datasetcatalog.DatasetManifestEntry;
import java.time.Instant;
import java.util.List;

public record DatasetPublicationWorkerResult(boolean verified, List<DatasetManifestEntry> manifest, long objectCount,
		long byteCount, Instant verifiedAt, long workerPid, String failureCode, boolean retryable) {

	public DatasetPublicationWorkerResult {
		manifest = manifest == null ? List.of() : List.copyOf(manifest);
	}

}
