package de.zorro909.skywright.backend.credential;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Brokers only the two storage identities consumed by the local Training Process. */
public class LocalCredentialProjections {

	public record Selection(UUID bindingId, String resource, String accessProfile) {
		public Selection {
			if (bindingId == null || resource == null || accessProfile == null) {
				throw new IllegalArgumentException("Invalid projection selection");
			}
		}
	}

	private final VaultBindings vault;

	private final LocalProjectionFacts facts;

	public LocalCredentialProjections(VaultBindings vault, LocalProjectionFacts facts) {
		this.vault = vault;
		this.facts = facts;
	}

	/**
	 * Call only for a new Run. Recovery reuses the orchestrator's retained secret
	 * channel.
	 */
	@org.springframework.transaction.annotation.Transactional
	public TrainingCredentials training(UUID runId, Selection dataset, Selection runStore, Instant requiredUntil) {
		if (dataset.bindingId().equals(runStore.bindingId()) || dataset.resource().equals(runStore.resource())) {
			throw new IllegalArgumentException("Dataset and Run Store require separate resources and identities");
		}
		if (!dataset.accessProfile().equals("read-only") || !runStore.accessProfile().equals("read-write-delete")) {
			throw new IllegalArgumentException(
					"Training storage profiles must isolate Dataset read and Run Store writes");
		}
		var datasetBinding = trainingBinding(dataset);
		var runStoreBinding = trainingBinding(runStore);
		if (datasetBinding.identity().equals(runStoreBinding.identity())) {
			throw new IllegalArgumentException("Dataset and Run Store require distinct external identities");
		}
		var values = new LinkedHashMap<String, String>();
		project(runId, "dataset", datasetBinding, "SKYWRIGHT_DATASET", requiredUntil, values);
		project(runId, "run-store", runStoreBinding, "SKYWRIGHT_RUN_STORE", requiredUntil, values);
		return new TrainingCredentials(values);
	}

	/** The target runtime retains its immutable pull secret through recovery. */
	@org.springframework.transaction.annotation.Transactional
	public RuntimePullProjection runtimePull(UUID runId, Selection registry, Instant requiredUntil,
			java.nio.file.Path temporaryDirectory) {
		var binding = this.vault.definitions()
			.stream()
			.filter(b -> b.id().equals(registry.bindingId()) && b.kind() == CredentialBinding.Kind.GHCR
					&& b.role().equals("execution-target-pull") && b.resource().equals(registry.resource())
					&& b.accessProfile().equals("read-only") && b.accessProfile().equals(registry.accessProfile()))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("Runtime pull binding is unavailable"));
		if (requiredUntil == null || binding.validUntil() != null && !binding.validUntil().isAfter(requiredUntil)) {
			throw new IllegalStateException("Credential validity does not cover the Run recovery window");
		}
		this.facts.begin(runId, "runtime-pull", binding);
		var material = this.vault
			.resolve(binding.id(), binding.revision(), "execution-target-pull",
					secret -> java.util.List.of(secret.path("username").asText(), secret.path("token").asText()))
			.value()
			.orElseThrow(() -> new IllegalStateException("Runtime pull binding is unavailable"));
		var projection = new RuntimePullProjection(temporaryDirectory, material.get(0), material.get(1));
		if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
			org.springframework.transaction.support.TransactionSynchronizationManager
				.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
					@Override
					public void afterCompletion(int status) {
						if (status != STATUS_COMMITTED) {
							projection.close();
						}
					}
				});
		}
		return projection;
	}

	private CredentialBinding trainingBinding(Selection selection) {
		return this.vault.definitions()
			.stream()
			.filter(b -> b.id().equals(selection.bindingId()) && b.kind() == CredentialBinding.Kind.S3
					&& b.role().equals("training-process") && b.resource().equals(selection.resource())
					&& b.accessProfile().equals(selection.accessProfile()))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("Training Credential Binding is unavailable"));
	}

	private void project(UUID runId, String slot, CredentialBinding binding, String prefix, Instant requiredUntil,
			LinkedHashMap<String, String> values) {
		if (requiredUntil == null || binding.validUntil() != null && !binding.validUntil().isAfter(requiredUntil)) {
			throw new IllegalStateException("Credential validity does not cover the Run recovery window");
		}
		this.facts.begin(runId, slot, binding);
		var result = this.vault.resolve(binding.id(), binding.revision(), "training-process", secret -> {
			values.put(prefix + "_ACCESS_KEY_ID", secret.path("accessKeyId").asText());
			values.put(prefix + "_SECRET_ACCESS_KEY", secret.path("secretAccessKey").asText());
			if (secret.has("sessionToken")) {
				values.put(prefix + "_SESSION_TOKEN", secret.path("sessionToken").asText());
			}
			return Boolean.TRUE;
		});
		if (result.status() != VaultBindings.Status.READY) {
			values.clear();
			throw new IllegalStateException("Training Credential Binding is " + result.status());
		}
	}

}
