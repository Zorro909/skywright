package de.zorro909.skywright.backend.credential;

import java.util.UUID;
import java.util.function.Function;

/** Only the backend's service-account binding can authorize its SkyPilot calls. */
public final class BackendSkyPilotAuthorization {

	private final VaultBindings vault;

	private final UUID bindingId;

	private final String endpoint;

	public BackendSkyPilotAuthorization(VaultBindings vault, UUID bindingId, String endpoint) {
		this.vault = vault;
		this.bindingId = bindingId;
		this.endpoint = endpoint;
	}

	public <T> T use(Function<String, T> request) {
		var binding = this.vault.definitions()
			.stream()
			.filter(b -> b.id().equals(this.bindingId) && b.kind() == CredentialBinding.Kind.SKYPILOT
					&& b.role().equals("backend") && b.resource().equals(this.endpoint))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("Backend SkyPilot binding is unavailable"));
		return this.vault
			.resolve(binding.id(), binding.revision(), "backend",
					secret -> request.apply(secret.path("token").asText()))
			.value()
			.orElseThrow(() -> new IllegalStateException("Backend SkyPilot authorization failed"));
	}

}
