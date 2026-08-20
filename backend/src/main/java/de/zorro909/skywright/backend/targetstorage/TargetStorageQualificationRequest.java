package de.zorro909.skywright.backend.targetstorage;

import java.util.List;
import java.util.UUID;

record TargetStorageQualificationRequest(UUID storageId, TargetStoragePurpose purpose, String bucket,
		long configurationRevision, TargetStorageConfiguration configuration, List<TargetStorageBinding> bindings) {
}
