package de.zorro909.skywright.backend.targetstorage;

import java.util.UUID;

public record RepatriationOverride(boolean enabled, UUID storageId) {
}
