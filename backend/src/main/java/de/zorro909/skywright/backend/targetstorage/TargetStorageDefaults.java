package de.zorro909.skywright.backend.targetstorage;

import java.util.UUID;

record TargetStorageDefaults(TargetClass targetClass, UUID executionStorageId, boolean repatriationEnabled,
		UUID repatriationStorageId) {
}
