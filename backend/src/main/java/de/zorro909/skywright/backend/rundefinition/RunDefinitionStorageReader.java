package de.zorro909.skywright.backend.rundefinition;

import de.zorro909.skywright.backend.targetstorage.RunDefinitionStorageOverrides;
import de.zorro909.skywright.backend.targetstorage.RunDefinitionStorageSelection;
import de.zorro909.skywright.backend.targetstorage.TargetClass;

/** Narrow read port for qualified definition-owned Target Storage snapshots. */
@FunctionalInterface
public interface RunDefinitionStorageReader {

	RunDefinitionStorageSelection resolve(TargetClass targetClass, RunDefinitionStorageOverrides overrides);

}
