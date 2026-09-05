package de.zorro909.skywright.backend.credential;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "local_credential_release")
class LocalProjectionRelease {

	@Id
	@Column(name = "consumer_id")
	UUID consumerId;

	@Column(name = "released_at", nullable = false)
	Instant releasedAt;

	protected LocalProjectionRelease() {
	}

	LocalProjectionRelease(UUID consumerId, Instant releasedAt) {
		this.consumerId = consumerId;
		this.releasedAt = releasedAt;
	}

}
