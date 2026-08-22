package de.zorro909.skywright.backend.datasetcatalog;

import java.time.Instant;
import java.util.UUID;

public record DatasetCopyOperationView(UUID id, DatasetCopyOperationKind kind, UUID copyId, long generation,
		DatasetCopyOperationProgress progress, DatasetCopyOperationProgress failedProgress, int attempts,
		String failureCode, String failureSummary, boolean retryable, Instant startedAt, Instant updatedAt) {

	public DatasetCopyOperationView(UUID id, DatasetCopyOperationKind kind, UUID copyId, long generation,
			DatasetCopyOperationProgress progress, int attempts, String failureCode, String failureSummary,
			boolean retryable, Instant startedAt, Instant updatedAt) {
		this(id, kind, copyId, generation, progress, null, attempts, failureCode, failureSummary, retryable, startedAt,
				updatedAt);
	}

	public boolean active() {
		return this.progress != DatasetCopyOperationProgress.COMPLETED
				&& this.progress != DatasetCopyOperationProgress.CANCELLED
				&& this.progress != DatasetCopyOperationProgress.FAILED;
	}

}
