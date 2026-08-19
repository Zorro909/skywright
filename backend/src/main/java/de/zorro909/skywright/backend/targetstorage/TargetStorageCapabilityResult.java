package de.zorro909.skywright.backend.targetstorage;

import java.util.Map;

record TargetStorageCapabilityResult(String capability, boolean succeeded, String failureCode, String summary,
		Map<String, String> observations) {

	TargetStorageCapabilityResult {
		if (capability == null || capability.isBlank()) {
			throw new IllegalArgumentException("capability must not be blank");
		}
		if (!(observations = Map.copyOf(observations)).isEmpty()) {
			throw new IllegalArgumentException("free-form capability observations are forbidden");
		}
		if (succeeded && (failureCode != null || summary != null)) {
			throw new IllegalArgumentException("successful capability cannot carry a failure");
		}
		if (!(succeeded || failureCode != null && summary != null)) {
			throw new IllegalArgumentException("failed capability requires a code and summary");
		}
	}

	static TargetStorageCapabilityResult success(String capability) {
		return new TargetStorageCapabilityResult(capability, true, null, null, Map.of());
	}

	static TargetStorageCapabilityResult failure(String capability, String code, String summary,
			Map<String, String> observations) {
		return new TargetStorageCapabilityResult(capability, false, code, summary, observations);
	}
}
