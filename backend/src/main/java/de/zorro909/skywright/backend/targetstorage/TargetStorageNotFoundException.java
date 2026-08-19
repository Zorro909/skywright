package de.zorro909.skywright.backend.targetstorage;

import java.util.UUID;

final class TargetStorageNotFoundException extends TargetStorageException {

	TargetStorageNotFoundException(UUID id) {
		super("TARGET_STORAGE_NOT_FOUND", "Target Storage " + id + " does not exist");
	}

}
