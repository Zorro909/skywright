package de.zorro909.skywright.backend.targetstorage;

import de.zorro909.skywright.backend.rundefinition.RunDefinitionStorageReader;
import de.zorro909.skywright.backend.rundefinition.RunDefinitionStorageException;

/** Production Run Definition adapter over the Target Storage selection boundary. */
public final class TargetStorageRunDefinitionReader implements RunDefinitionStorageReader {

	private final TargetStorageRegistry registry;

	public TargetStorageRunDefinitionReader(TargetStorageRegistry registry) {
		this.registry = registry;
	}

	@Override
	public RunDefinitionStorageSelection resolve(TargetClass targetClass, RunDefinitionStorageOverrides overrides) {
		try {
			return this.registry.resolveForRunDefinition(targetClass, overrides);
		}
		catch (TargetStorageException error) {
			throw new RunDefinitionStorageException(error.code(), "Target Storage selection failed", error);
		}
	}

}
