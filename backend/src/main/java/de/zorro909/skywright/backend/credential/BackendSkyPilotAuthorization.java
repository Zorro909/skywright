package de.zorro909.skywright.backend.credential;

import java.util.UUID;
import java.util.function.Function;

/** Only the backend's service-account binding can authorize its SkyPilot calls. */
public final class BackendSkyPilotAuthorization {

	private final VaultBindings vault;

	private final UUID bindingId;

	private final String endpoint;

	private final LocalProjectionFacts facts;

	public BackendSkyPilotAuthorization(VaultBindings vault, UUID bindingId, String endpoint,
			LocalProjectionFacts facts) {
		this.vault = vault;
		this.bindingId = bindingId;
		this.endpoint = endpoint;
		this.facts = facts;
	}

	public <T> T use(Function<String, T> request) {
		var binding = this.vault.definitions()
			.stream()
			.filter(b -> b.id().equals(this.bindingId) && b.kind() == CredentialBinding.Kind.SKYPILOT
					&& b.role().equals("backend") && b.resource().equals(this.endpoint))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("Backend SkyPilot binding is unavailable"));
		return this.vault.resolve(binding.id(), binding.revision(), "backend", secret -> {
			var operationId = UUID.randomUUID();
			this.facts.begin(operationId, "backend", binding);
			try {
				return request.apply(secret.path("token").asText());
			}
			finally {
				this.facts.release(operationId);
			}
		}).value().orElseThrow(() -> new IllegalStateException("Backend SkyPilot authorization failed"));
	}

}
