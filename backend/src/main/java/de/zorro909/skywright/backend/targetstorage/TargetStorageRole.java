package de.zorro909.skywright.backend.targetstorage;

public enum TargetStorageRole {

	TRAINING_PROCESS, BACKEND, TRANSFER_WORKER, METRIC_VIEW;

	public String wireValue() {
		return this.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
	}

}
