package de.zorro909.skywright.backend.targetstorage;

final class TargetStorageReferencedException extends TargetStorageException {

	TargetStorageReferencedException() {
		super("TARGET_STORAGE_REFERENCED", "Referenced Target Storage cannot be deleted");
	}

}
