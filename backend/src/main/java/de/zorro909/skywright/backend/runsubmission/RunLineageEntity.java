package de.zorro909.skywright.backend.runsubmission;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Accepted Skywright relationship, never inferred from a missing external record. */
@Entity
@Table(name = "run_lineage", schema = "skywright")
class RunLineageEntity {

	@Id
	@Column(name = "run_id", updatable = false)
	UUID runId;

	@Column(name = "predecessor_run_id", updatable = false)
	UUID predecessorRunId;

	@Column(name = "checkpoint_reference", updatable = false)
	String checkpointReference;

	@Column(name = "seed_verified_at", updatable = false)
	Instant seedVerifiedAt;

	protected RunLineageEntity() {
	}

	RunLineageEntity(UUID runId, LocalRunRequest.CheckpointSeed seed, Instant verifiedAt) {
		this.runId = runId;
		if (seed != null) {
			this.predecessorRunId = seed.predecessorRunId();
			this.checkpointReference = seed.checkpointReference();
			this.seedVerifiedAt = java.util.Objects.requireNonNull(verifiedAt);
		}
	}

}
