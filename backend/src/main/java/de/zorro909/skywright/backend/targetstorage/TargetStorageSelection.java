package de.zorro909.skywright.backend.targetstorage;

import java.util.UUID;

public record TargetStorageSelection(UUID executionStorageId, boolean repatriationEnabled, UUID repatriationStorageId) {
}
