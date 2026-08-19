package de.zorro909.skywright.backend.targetstorage;

enum TargetStoragePurpose {

	DATASET("dataset"), RUN_OUTPUT("run-output");

	private final String wireValue;

	private TargetStoragePurpose(String wireValue) {
		this.wireValue = wireValue;
	}

	String wireValue() {
		return this.wireValue;
	}

}
