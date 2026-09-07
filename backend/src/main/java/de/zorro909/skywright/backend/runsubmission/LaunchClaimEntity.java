package de.zorro909.skywright.backend.runsubmission;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "run_launch_claim", schema = "skywright")
class LaunchClaimEntity {

	@Id
	@Column(name = "run_id")
	UUID runId;

	@Column(name = "task_fingerprint", nullable = false, updatable = false)
	String fingerprint;

	@Column(name = "claimed_at", nullable = false, updatable = false)
	Instant claimedAt;

	protected LaunchClaimEntity() {
	}

	LaunchClaimEntity(UUID runId, String fingerprint) {
		this.runId = runId;
		this.fingerprint = fingerprint;
		claimedAt = Instant.now();
	}

}
