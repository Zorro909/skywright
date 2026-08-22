package de.zorro909.skywright.backend.datasetcatalog;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.Instant;
import java.util.UUID;

@Embeddable
class DatasetLeaseEmbeddable {

	@Column(name = "lease_id", nullable = false)
	UUID id;

	@Column(name = "run_record_id", nullable = false)
	UUID runRecordId;

	@Column(name = "copy_id", nullable = false)
	UUID copyId;

	@Column(name = "generation_number", nullable = false)
	long generation;

	@Column(name = "acquired_at", nullable = false)
	Instant acquiredAt;

	@Column(name = "ended_at")
	Instant endedAt;

	@Column(name = "end_reason")
	String endReason;

	protected DatasetLeaseEmbeddable() {
	}

	static DatasetLeaseEmbeddable from(DatasetLeaseView value) {
		DatasetLeaseEmbeddable result = new DatasetLeaseEmbeddable();
		result.id = value.id();
		result.runRecordId = value.runRecordId();
		result.copyId = value.copyId();
		result.generation = value.generation();
		result.acquiredAt = value.acquiredAt();
		result.endedAt = value.endedAt();
		result.endReason = value.endReason();
		return result;
	}

	DatasetLeaseView domain(UUID definitionId) {
		return new DatasetLeaseView(this.id, this.runRecordId, definitionId, this.copyId, this.generation,
				this.acquiredAt, this.endedAt, this.endReason);
	}

}
