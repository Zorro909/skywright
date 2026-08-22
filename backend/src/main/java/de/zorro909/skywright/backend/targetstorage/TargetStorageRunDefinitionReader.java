package de.zorro909.skywright.backend.targetstorage;

import java.util.UUID;

import de.zorro909.skywright.backend.rundefinition.RunDefinitionStorageReader;

/** Production Run Definition adapter over the Target Storage selection boundary. */
public final class TargetStorageRunDefinitionReader implements RunDefinitionStorageReader {

	private final TargetStorageRegistry registry;

	public TargetStorageRunDefinitionReader(TargetStorageRegistry registry) {
		this.registry = registry;
	}

	@Override
	public RunDefinitionStorageSelection resolve(String targetClass, UUID executionOverride,
			Boolean repatriationEnabledOverride, UUID repatriationStorageOverride) {
		return this.registry.resolveForRunDefinition(targetClass, executionOverride, repatriationEnabledOverride,
				repatriationStorageOverride);
	}

}
