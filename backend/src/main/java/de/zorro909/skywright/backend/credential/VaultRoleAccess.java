package de.zorro909.skywright.backend.credential;

import java.util.Optional;
import java.util.UUID;
import de.zorro909.skywright.backend.targetstorage.BindingReadiness;
import de.zorro909.skywright.backend.targetstorage.TargetStorageBindingReadiness;
import de.zorro909.skywright.backend.targetstorage.TargetStorageCredentialAccess;
import de.zorro909.skywright.backend.trainingproject.RegistryReadiness;
import de.zorro909.skywright.backend.trainingproject.TrainingProjectCredentialReadiness;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

/** Adapts existing capability checks without making a missing binding globally fatal. */
public final class VaultRoleAccess
		implements TargetStorageBindingReadiness, TargetStorageCredentialAccess, TrainingProjectCredentialReadiness {

	private final VaultBindings bindings;

	public VaultRoleAccess(VaultBindings bindings) {
		this.bindings = bindings;
	}

	@Override
	public BindingReadiness readiness(UUID id, long revision, String role) {
		if (!kind(id, CredentialBinding.Kind.S3)) {
			return BindingReadiness.MISSING;
		}
		return BindingReadiness.valueOf(this.bindings.readiness(id, revision, role).status().name());
	}

	@Override
	public RegistryReadiness readiness(UUID id, String role, String repository) {
		var binding = this.bindings.definitions()
			.stream()
			.filter(b -> b.id().equals(id) && b.kind() == CredentialBinding.Kind.GHCR)
			.findFirst();
		return binding
			.map(b -> !b.resource().equals(repository) ? RegistryReadiness.INVALID
					: RegistryReadiness.valueOf(this.bindings.readiness(id, b.revision(), role).status().name()))
			.orElse(RegistryReadiness.MISSING);
	}

	@Override
	public Optional<AwsCredentialsProvider> credentials(UUID id, long revision, String role) {
		// Backend storage operations may never borrow a Training Process identity.
		if (!("backend".equals(role) || "transfer-worker".equals(role)) || !kind(id, CredentialBinding.Kind.S3)) {
			return Optional.empty();
		}
		return this.bindings.<AwsCredentialsProvider>resolve(id, revision, role, secret -> {
			var key = secret.path("accessKeyId").asText();
			var password = secret.path("secretAccessKey").asText();
			var token = secret.path("sessionToken").asText("");
			return StaticCredentialsProvider.create(token.isEmpty() ? AwsBasicCredentials.create(key, password)
					: AwsSessionCredentials.create(key, password, token));
		}).value();
	}

	private boolean kind(UUID id, CredentialBinding.Kind kind) {
		return this.bindings.definitions().stream().anyMatch(b -> b.id().equals(id) && b.kind() == kind);
	}

}
