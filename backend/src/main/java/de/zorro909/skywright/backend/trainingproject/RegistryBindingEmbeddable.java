package de.zorro909.skywright.backend.trainingproject;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.util.UUID;

@Embeddable
class RegistryBindingEmbeddable {

	@Column(name = "binding_revision", nullable = false)
	long revision;

	@Column(nullable = false)
	String repository;

	@Enumerated(EnumType.STRING)
	@Column(name = "access_mode", nullable = false)
	RegistryAccessMode accessMode;

	@Column(name = "resolver_credential_binding_id")
	UUID resolverCredentialBindingId;

	@Column(name = "execution_credential_binding_id")
	UUID executionCredentialBindingId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	RegistryReadiness readiness;

	@Column(nullable = false)
	String state;

	protected RegistryBindingEmbeddable() {
	}

	static RegistryBindingEmbeddable from(RegistryBinding value) {
		var result = new RegistryBindingEmbeddable();
		result.revision = value.revision();
		result.repository = value.repository();
		result.accessMode = value.accessMode();
		result.resolverCredentialBindingId = value.resolverCredentialBindingId();
		result.executionCredentialBindingId = value.executionCredentialBindingId();
		result.readiness = value.readiness();
		result.state = value.state();
		return result;
	}

	RegistryBinding domain() {
		return new RegistryBinding(this.revision, this.repository, this.accessMode, this.resolverCredentialBindingId,
				this.executionCredentialBindingId, this.readiness, this.state);
	}

}
