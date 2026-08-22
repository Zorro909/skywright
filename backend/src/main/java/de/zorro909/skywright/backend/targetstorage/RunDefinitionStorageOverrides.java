package de.zorro909.skywright.backend.targetstorage;

import java.util.UUID;

/** Optional submission overrides applied while selecting definition-owned storage. */
public record RunDefinitionStorageOverrides(UUID executionStorage, Boolean repatriationEnabled,
		UUID repatriationStorage) {

	public static RunDefinitionStorageOverrides none() {
		return new RunDefinitionStorageOverrides(null, null, null);
	}

}
