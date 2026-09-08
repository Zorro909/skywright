package de.zorro909.skywright.backend.datasetcatalog;

import java.util.UUID;

record DatasetCopyWorkerReceipt(UUID attemptId, long workerPid, String failureCode,
		VerifiedDatasetReplacement replacement) {
}
