package de.zorro909.skywright.backend.targetstorage;

/** Persistence mapping value shared by the typed configuration row and aggregate. */
final class TargetStorageEncoding {

	private TargetStorageEncoding() {
	}

	record DecodedConfiguration(long revision, TargetStorageConfiguration configuration) {
	}

}
