package de.zorro909.skywright.backend.targetstorage;

enum TargetClass {

	LOCAL_SINGLE_GPU("local-single-gpu"), LOCAL_MULTI_GPU("local-multi-gpu"), CLOUD_ON_DEMAND("cloud-on-demand"),
	CLOUD_SPOT("cloud-spot");

	private final String wireValue;

	private TargetClass(String wireValue) {
		this.wireValue = wireValue;
	}

	String wireValue() {
		return this.wireValue;
	}

}
