package de.zorro909.skywright.backend.targetstorage;

import de.zorro909.skywright.backend.runstore.ResolvedTargetStorage;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;

/** Resolves an eligible registration into the existing production Run Store seam. */
@Component
@ConditionalOnBean(TargetStorageCredentialAccess.class)
public final class TargetStorageResolver {

	private final TargetStorageRegistry registry;

	private final TargetStorageCredentialAccess credentials;

	TargetStorageResolver(TargetStorageRegistry registry, TargetStorageCredentialAccess credentials) {
		this.registry = registry;
		this.credentials = credentials;
	}

	public ResolvedTargetStorage resolveRunOutput(UUID storageId, String consumingRole, String trainingProjectId,
			String runId) {
		TargetStorageRole role = TargetStorageRole.fromWireValue(consumingRole);
		TargetStorageResolution resolution = this.registry.resolveEligibleRunOutput(storageId, role);
		TargetStorageDescriptor descriptor = resolution.descriptor();
		TargetStorageBinding binding = resolution.binding();
		var provider = this.credentials.credentials(binding.bindingId(), binding.bindingRevision(), role.wireValue())
			.orElseThrow(() -> new TargetStorageIneligibleException("TARGET_STORAGE_CREDENTIALS_UNAVAILABLE",
					"The required Credential Projection is unavailable"));
		return new ResolvedTargetStorage(descriptor.storageId().toString(), descriptor.endpoint(), descriptor.bucket(),
				Region.of(descriptor.region()), descriptor.pathStyleAccess(), descriptor.compatibilityOptions(),
				provider, trainingProjectId, runId);
	}

}
