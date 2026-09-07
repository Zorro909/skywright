package de.zorro909.skywright.backend.runsubmission;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Append-only non-secret projection and process ownership facts. */
@Entity
@Table(name = "local_seed_worker_event", schema = "skywright")
class LocalSeedWorkerEventEntity {

	@Id
	UUID id;

	@Column(name = "operation_id", nullable = false, updatable = false)
	UUID operationId;

	@Column(nullable = false, updatable = false)
	String kind;

	@Column(name = "recorded_at", nullable = false, updatable = false)
	Instant recordedAt;

	@Column(nullable = false, updatable = false, columnDefinition = "text")
	String payload;

	protected LocalSeedWorkerEventEntity() {
	}

	LocalSeedWorkerEventEntity(UUID operationId, String kind, Instant at, String payload) {
		this.id = UUID.randomUUID();
		this.operationId = operationId;
		this.kind = kind;
		this.recordedAt = at;
		this.payload = payload;
	}

}
