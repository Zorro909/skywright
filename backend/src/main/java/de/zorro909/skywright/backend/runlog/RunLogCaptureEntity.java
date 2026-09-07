package de.zorro909.skywright.backend.runlog;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "run_log_capture", schema = "skywright")
class RunLogCaptureEntity {

	@Id
	@Column(name = "run_id")
	UUID runId;

	@Column(name = "checkpoint_json", nullable = false, columnDefinition = "text")
	String checkpoint;

	@Column(name = "lease_token")
	UUID leaseToken;

	@Column(name = "lease_until")
	Instant leaseUntil;

	@Column(name = "next_attempt_at")
	Instant nextAttemptAt;

	@Column(name = "manifest_sha256")
	String manifestSha256;

	@Column(name = "finalized_at")
	Instant finalizedAt;

	protected RunLogCaptureEntity() {
	}

}
