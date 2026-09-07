package de.zorro909.skywright.backend.targetstorage;

import de.zorro909.skywright.backend.runstore.ResolvedTargetStorage;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;

/** Resolves an eligible registration into the existing production Run Store seam. */
@Component
public final class TargetStorageResolver {

	private final TargetStorageRegistry registry;

	private final TargetStorageCredentialAccess credentials;

	@Autowired
	TargetStorageResolver(TargetStorageRegistry registry, ObjectProvider<TargetStorageCredentialAccess> credentials) {
		this.registry = registry;
		this.credentials = credentials.getIfAvailable();
	}

	TargetStorageResolver(TargetStorageRegistry registry, TargetStorageCredentialAccess credentials) {
		this.registry = registry;
		this.credentials = credentials;
	}

	public ResolvedTargetStorage resolveRunOutput(UUID storageId, String consumingRole, String trainingProjectId,
			String runId) {
		TargetStorageRole role = TargetStorageRole.fromWireValue(consumingRole);
		TargetStorageResolution resolution = this.registry.resolveEligibleRunOutput(storageId, role);
		return resolve(resolution, role, trainingProjectId, runId);
	}

	public ResolvedTargetStorage resolveRunOutputRead(tools.jackson.databind.JsonNode location,
			String trainingProjectId, String runId) {
		UUID storageId = UUID.fromString(location.path("storageId").asText());
		var current = this.registry.resolveRunOutputRead(storageId);
		var options = new java.util.HashMap<String, String>();
		location.path("compatibilityOptions").properties().forEach(e -> options.put(e.getKey(), e.getValue().asText()));
		var pinned = new TargetStorageDescriptor(storageId, java.net.URI.create(location.path("endpoint").asText()),
				location.path("bucket").asText(), location.path("region").asText(),
				location.path("addressingMode").asText().equals("path"), java.util.Map.copyOf(options));
		return resolve(new TargetStorageResolution(pinned, current.binding()), TargetStorageRole.BACKEND,
				trainingProjectId, runId);
	}

	private ResolvedTargetStorage resolve(TargetStorageResolution resolution, TargetStorageRole role,
			String trainingProjectId, String runId) {
		TargetStorageDescriptor descriptor = resolution.descriptor();
		TargetStorageBinding binding = resolution.binding();
		var provider = this.credentials()
			.credentials(binding.bindingId(), binding.bindingRevision(), role.wireValue())
			.orElseThrow(() -> new TargetStorageIneligibleException("TARGET_STORAGE_CREDENTIALS_UNAVAILABLE",
					"The required Credential Projection is unavailable"));
		return new ResolvedTargetStorage(descriptor.storageId().toString(), descriptor.endpoint(), descriptor.bucket(),
				Region.of(descriptor.region()), descriptor.pathStyleAccess(), descriptor.compatibilityOptions(),
				provider, trainingProjectId, runId, binding.bindingId(), binding.bindingRevision());
	}

	public ResolvedTargetStorage resolveDataset(UUID storageId, String consumingRole) {
		TargetStorageRole role = TargetStorageRole.fromWireValue(consumingRole);
		TargetStorageResolution resolution = this.registry.resolveDatasetMaintenance(storageId, role);
		TargetStorageDescriptor descriptor = resolution.descriptor();
		TargetStorageBinding binding = resolution.binding();
		var provider = this.credentials()
			.credentials(binding.bindingId(), binding.bindingRevision(), role.wireValue())
			.orElseThrow(() -> new TargetStorageIneligibleException("TARGET_STORAGE_CREDENTIALS_UNAVAILABLE",
					"The required Credential Projection is unavailable"));
		return new ResolvedTargetStorage(descriptor.storageId().toString(), descriptor.endpoint(), descriptor.bucket(),
				Region.of(descriptor.region()), descriptor.pathStyleAccess(), descriptor.compatibilityOptions(),
				provider, "dataset-catalog", "maintenance", binding.bindingId(), binding.bindingRevision());
	}

	private TargetStorageCredentialAccess credentials() {
		if (this.credentials == null) {
			throw new TargetStorageIneligibleException("TARGET_STORAGE_CREDENTIALS_UNAVAILABLE",
					"The required Credential Projection is unavailable");
		}
		return this.credentials;
	}

}
