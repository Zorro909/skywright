package de.zorro909.skywright.backend.targetstorage;

enum TargetStorageRole {

	TRAINING_PROCESS, BACKEND, TRANSFER_WORKER, METRIC_VIEW;

	String wireValue() {
		return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
	}

	static TargetStorageRole fromWireValue(String value) {
		return java.util.Arrays.stream(values())
			.filter(role -> role.wireValue().equals(value))
			.findFirst()
			.orElseThrow(() -> new TargetStorageValidationException("TARGET_STORAGE_ROLE_INVALID",
					"Unknown Target Storage role"));
	}

}
