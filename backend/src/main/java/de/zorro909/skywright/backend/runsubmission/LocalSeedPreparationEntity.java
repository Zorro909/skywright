package de.zorro909.skywright.backend.runsubmission;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Owns the destination inventory even when acceptance rolls back or its caller
 * disappears.
 */
@Entity
@Table(name = "local_seed_preparation", schema = "skywright")
class LocalSeedPreparationEntity {

	@Id
	@Column(name = "submission_id", updatable = false)
	UUID submissionId;

	@Column(name = "run_id", nullable = false, updatable = false)
	UUID runId;

	@Column(name = "request_digest", nullable = false, updatable = false)
	String requestDigest;

	@Column(name = "predecessor_run_id", nullable = false, updatable = false)
	UUID predecessorRunId;

	@Column(name = "checkpoint_reference", nullable = false, updatable = false)
	String checkpointReference;

	@Column(name = "definition_json", columnDefinition = "text")
	String definition;

	@Column(name = "lease_token")
	UUID leaseToken;

	@Column(name = "lease_until")
	Instant leaseUntil;

	@Column(name = "verified_at")
	Instant verifiedAt;

	@Column(name = "receipt_json", columnDefinition = "text")
	String receipt;

	protected LocalSeedPreparationEntity() {
	}

	LocalSeedPreparationEntity(LocalRunRequest request, String digest) {
		submissionId = request.submissionId();
		runId = UUID.randomUUID();
		requestDigest = digest;
		predecessorRunId = request.checkpointSeed().predecessorRunId();
		checkpointReference = request.checkpointSeed().checkpointReference();
	}

}
