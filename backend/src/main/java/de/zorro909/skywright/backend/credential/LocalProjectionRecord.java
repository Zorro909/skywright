package de.zorro909.skywright.backend.credential;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "local_credential_projection")
class LocalProjectionRecord {

	@Id
	UUID id;

	@Column(name = "consumer_id", nullable = false)
	UUID consumerId;

	@Column(nullable = false)
	String slot;

	@Column(name = "binding_id", nullable = false)
	UUID bindingId;

	@Column(name = "binding_revision", nullable = false)
	long bindingRevision;

	@Column(name = "consumer_role", nullable = false)
	String consumerRole;

	@Column(name = "projected_at", nullable = false)
	Instant projectedAt;

	protected LocalProjectionRecord() {
	}

	LocalProjectionRecord(UUID consumerId, String slot, CredentialBinding binding, Instant projectedAt) {
		this.id = UUID.randomUUID();
		this.consumerId = consumerId;
		this.slot = slot;
		this.bindingId = binding.id();
		this.bindingRevision = binding.revision();
		this.consumerRole = binding.role();
		this.projectedAt = projectedAt;
	}

}
