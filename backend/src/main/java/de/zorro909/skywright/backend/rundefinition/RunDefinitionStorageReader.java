package de.zorro909.skywright.backend.rundefinition;

import java.util.UUID;

import de.zorro909.skywright.backend.targetstorage.RunDefinitionStorageSelection;

/** Narrow read port for qualified definition-owned Target Storage snapshots. */
@FunctionalInterface
public interface RunDefinitionStorageReader {

	RunDefinitionStorageSelection resolve(String targetClass, UUID executionOverride,
			Boolean repatriationEnabledOverride, UUID repatriationStorageOverride);

}
