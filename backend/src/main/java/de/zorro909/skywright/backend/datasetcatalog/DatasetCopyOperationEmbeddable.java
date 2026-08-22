package de.zorro909.skywright.backend.datasetcatalog;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.Instant;
import java.util.UUID;

@Embeddable
class DatasetCopyOperationEmbeddable {

	@Column(name = "operation_id", nullable = false)
	UUID id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	DatasetCopyOperationKind kind;

	@Column(name = "copy_id", nullable = false)
	UUID copyId;

	@Column(name = "generation_number", nullable = false)
	long generation;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	DatasetCopyOperationProgress progress;

	@Column(nullable = false)
	int attempts;

	@Column(name = "failure_code")
	String failureCode;

	@Column(name = "failure_summary", length = 1024)
	String failureSummary;

	@Column(nullable = false)
	boolean retryable;

	@Column(name = "started_at", nullable = false)
	Instant startedAt;

	@Column(name = "updated_at", nullable = false)
	Instant updatedAt;

	protected DatasetCopyOperationEmbeddable() {
	}

	static DatasetCopyOperationEmbeddable from(DatasetCopyOperationView value) {
		DatasetCopyOperationEmbeddable result = new DatasetCopyOperationEmbeddable();
		result.id = value.id();
		result.kind = value.kind();
		result.copyId = value.copyId();
		result.generation = value.generation();
		result.progress = value.progress();
		result.attempts = value.attempts();
		result.failureCode = value.failureCode();
		result.failureSummary = value.failureSummary();
		result.retryable = value.retryable();
		result.startedAt = value.startedAt();
		result.updatedAt = value.updatedAt();
		return result;
	}

	DatasetCopyOperationView domain() {
		return new DatasetCopyOperationView(this.id, this.kind, this.copyId, this.generation, this.progress,
				this.attempts, this.failureCode, this.failureSummary, this.retryable, this.startedAt, this.updatedAt);
	}

}
