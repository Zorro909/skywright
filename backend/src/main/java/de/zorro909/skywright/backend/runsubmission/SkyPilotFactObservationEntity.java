package de.zorro909.skywright.backend.runsubmission;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Fetch metadata is retained separately so repeated fetches do not duplicate source
 * facts.
 */
@Entity
@Table(name = "skypilot_fact_observation", schema = "skywright")
class SkyPilotFactObservationEntity {

	@Id
	UUID id;

	@Column(name = "fact_id", nullable = false, updatable = false)
	UUID factId;

	@Column(name = "observed_at", nullable = false, updatable = false)
	Instant observedAt;

	protected SkyPilotFactObservationEntity() {
	}

	SkyPilotFactObservationEntity(UUID factId, Instant observedAt) {
		id = UUID.randomUUID();
		this.factId = factId;
		this.observedAt = observedAt;
	}

}
