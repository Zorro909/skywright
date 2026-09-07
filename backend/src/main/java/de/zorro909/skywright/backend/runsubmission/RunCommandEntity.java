package de.zorro909.skywright.backend.runsubmission;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "run_command", schema = "skywright")
class RunCommandEntity {

	@Id
	UUID id;

	@Column(name = "run_id", nullable = false, updatable = false)
	UUID runId;

	@Column(nullable = false, updatable = false)
	String kind;

	@Column(name = "accepted_at", nullable = false, updatable = false)
	Instant acceptedAt;

	@Column(name = "evidence_json", nullable = false, updatable = false, columnDefinition = "text")
	String evidence;

	protected RunCommandEntity() {
	}

	RunCommandEntity(UUID id, UUID run, RunCommand.Kind kind, Instant at, String evidence) {
		this.id = id;
		this.runId = run;
		this.kind = kind.name();
		this.acceptedAt = at;
		this.evidence = evidence;
	}

}
