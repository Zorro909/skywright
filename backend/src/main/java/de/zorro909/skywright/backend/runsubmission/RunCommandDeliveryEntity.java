package de.zorro909.skywright.backend.runsubmission;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "run_command_delivery", schema = "skywright")
class RunCommandDeliveryEntity {

	@Id
	@Column(name = "command_id")
	UUID commandId;

	@Column(nullable = false)
	String disposition;

	@Column(name = "projected_at")
	Instant projectedAt;

	@Column(name = "force_after")
	Instant forceAfter;

	@Column(name = "stop_attempted_at")
	Instant stopAttemptedAt;

	@Column(name = "next_attempt_at")
	Instant nextAttemptAt;

	@Column(name = "lease_token")
	UUID lease;

	@Column(name = "lease_until")
	Instant leaseUntil;

	@Column(nullable = false)
	int attempts;

	protected RunCommandDeliveryEntity() {
	}

	RunCommandDeliveryEntity(RunCommandEntity command) {
		commandId = command.id;
		disposition = "accepted";
		nextAttemptAt = command.acceptedAt;
		if (command.kind.equals(RunCommand.Kind.CANCELLATION_REQUEST.name()))
			forceAfter = command.acceptedAt.plusSeconds(30);
	}

	RunCommand view(RunCommandEntity command) {
		return new RunCommand(command.id, command.runId, RunCommand.Kind.valueOf(command.kind), command.acceptedAt,
				command.evidence, disposition, projectedAt, forceAfter, stopAttemptedAt, nextAttemptAt, lease,
				attempts);
	}

}
