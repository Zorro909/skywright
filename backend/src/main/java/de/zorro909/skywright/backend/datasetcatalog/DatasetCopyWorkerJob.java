package de.zorro909.skywright.backend.datasetcatalog;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

record DatasetCopyWorkerJob(UUID attemptId, String action, URI endpoint, String bucket, String region,
		boolean pathStyleAccess, Map<String, String> compatibilityOptions, DatasetDefinitionView definition,
		List<DatasetManifestEntry> manifest, DatasetCopyView copy, UUID operationId, long generation,
		long timeoutMillis, long parentPid, java.time.Instant parentStartedAt) {
}
