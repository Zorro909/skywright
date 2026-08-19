package de.zorro909.skywright.backend.targetstorage;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

record TargetStorageDescriptor(UUID storageId, URI endpoint, String bucket, String region, boolean pathStyleAccess,
		Map<String, String> compatibilityOptions) {
}
