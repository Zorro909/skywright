package de.zorro909.skywright.backend.targetstorage;

import java.util.Set;

enum TargetStoragePurpose {

	DATASET("dataset"), RUN_OUTPUT("run-output");

	private final String wireValue;

	private TargetStoragePurpose(String wireValue) {
		this.wireValue = wireValue;
	}

	String wireValue() {
		return this.wireValue;
	}

	Set<TargetStorageRole> requiredRoles() {
		return this == DATASET
				? Set.of(TargetStorageRole.TRAINING_PROCESS, TargetStorageRole.BACKEND,
						TargetStorageRole.TRANSFER_WORKER)
				: Set.of(TargetStorageRole.TRAINING_PROCESS, TargetStorageRole.BACKEND,
						TargetStorageRole.TRANSFER_WORKER, TargetStorageRole.METRIC_VIEW);
	}

}
