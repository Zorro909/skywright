package de.zorro909.skywright.backend.targetstorage;

/** Complete execution and Repatriation storage selection for a Run Definition. */
public record RunDefinitionStorageSelection(RunDefinitionStorageSnapshot execution, boolean repatriationEnabled,
		RunDefinitionStorageSnapshot repatriationDestination) {
}
