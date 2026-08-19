package de.zorro909.skywright.backend.targetstorage;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.util.UUID;

@Embeddable
class TargetStorageBindingEmbeddable {

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	TargetStorageRole role;

	@Column(name = "binding_id", nullable = false)
	UUID bindingId;

	@Column(name = "binding_revision", nullable = false)
	long bindingRevision;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	BindingReadiness readiness;

	protected TargetStorageBindingEmbeddable() {
	}

	static TargetStorageBindingEmbeddable from(TargetStorageBinding binding) {
		var result = new TargetStorageBindingEmbeddable();
		result.role = binding.role();
		result.bindingId = binding.bindingId();
		result.bindingRevision = binding.bindingRevision();
		result.readiness = binding.readiness();
		return result;
	}

	TargetStorageBinding domain() {
		return new TargetStorageBinding(this.role, this.bindingId, this.bindingRevision, this.readiness);
	}

}
