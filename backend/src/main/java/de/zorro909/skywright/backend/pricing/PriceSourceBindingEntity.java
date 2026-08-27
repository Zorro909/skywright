package de.zorro909.skywright.backend.pricing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.util.UUID;

@Entity(name = "PriceSourceBindingEntity")
@Table(name = "price_source_binding")
class PriceSourceBindingEntity {

	@Id
	@Column(name = "binding_key")
	String bindingKey;

	@Column(name = "binding_revision", nullable = false)
	long bindingRevision;

	@Column(name = "price_source_id", nullable = false)
	UUID sourceId;

	@Column(name = "source_revision", nullable = false)
	long sourceRevision;

	@Column(name = "maximum_observation_age", nullable = false)
	String maximumObservationAge;

	@Version
	@Column(name = "persistence_version", nullable = false)
	long persistenceVersion;

	protected PriceSourceBindingEntity() {
	}

	PriceSourceBindingEntity(String bindingKey, long bindingRevision, UUID sourceId, long sourceRevision,
			Duration maximumObservationAge) {
		this.bindingKey = bindingKey;
		this.bindingRevision = bindingRevision;
		this.sourceId = sourceId;
		this.sourceRevision = sourceRevision;
		this.maximumObservationAge = maximumObservationAge.toString();
	}

	PriceSourceBindingView view() {
		return new PriceSourceBindingView(this.bindingKey, this.bindingRevision, this.sourceId, this.sourceRevision,
				Duration.parse(this.maximumObservationAge));
	}

}

record PriceSourceBindingView(String bindingKey, long bindingRevision, UUID sourceId, long sourceRevision,
		Duration maximumObservationAge) {
}
