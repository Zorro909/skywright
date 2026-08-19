package de.zorro909.skywright.backend.targetstorage;

import java.util.UUID;

record TargetStorageSelection(UUID executionStorageId, boolean repatriationEnabled, UUID repatriationStorageId) {
}
