package de.zorro909.skywright.backend.targetstorage;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

/** Qualified non-secret Target Storage descriptor retained by a Run Definition. */
public record RunDefinitionStorageSnapshot(UUID storageId, long registrationRevision, long configurationRevision,
		URI endpoint, String bucket, String region, boolean pathStyleAccess, Map<String, String> compatibilityOptions) {

	public RunDefinitionStorageSnapshot {
		compatibilityOptions = Map.copyOf(compatibilityOptions);
	}

}
