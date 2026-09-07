package de.zorro909.skywright.backend.runsubmission;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Recorded under the same Run lock as first dispatch, before any launch was claimed. */
@Entity
@Table(name = "run_dispatch_prevention", schema = "skywright")
class RunDispatchPreventionEntity {

	@Id
	@Column(name = "run_id")
	UUID runId;

	@Column(name = "command_id", nullable = false, updatable = false)
	UUID commandId;

	@Column(name = "prevented_at", nullable = false, updatable = false)
	Instant preventedAt;

	protected RunDispatchPreventionEntity() {
	}

	RunDispatchPreventionEntity(RunCommandEntity command) {
		runId = command.runId;
		commandId = command.id;
		preventedAt = command.acceptedAt;
	}

}
