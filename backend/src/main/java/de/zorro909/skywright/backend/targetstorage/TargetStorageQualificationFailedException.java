package de.zorro909.skywright.backend.targetstorage;

final class TargetStorageQualificationFailedException extends TargetStorageException {

	TargetStorageQualificationFailedException(CapabilityAvailability availability) {
		super("TARGET_STORAGE_QUALIFICATION_FAILED",
				"Target Storage qualification completed as " + availability.name().toLowerCase().replace('_', '-'));
	}

}
