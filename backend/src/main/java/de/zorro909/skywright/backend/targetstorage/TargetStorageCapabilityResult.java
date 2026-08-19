package de.zorro909.skywright.backend.targetstorage;

import java.util.List;
import java.util.Locale;
import java.util.Map;

record TargetStorageCapabilityResult(String capability, boolean succeeded, String failureCode, String summary,
		Map<String, String> observations) {

	private static final List<String> SECRET_MARKERS = List.of("secret", "password", "token", "credential",
			"authorization", "accesskey");

	TargetStorageCapabilityResult {
		if (capability == null || capability.isBlank()) {
			throw new IllegalArgumentException("capability must not be blank");
		}
		if ((observations = Map.copyOf(observations)).keySet()
			.stream()
			.map(key -> key.toLowerCase(Locale.ROOT))
			.anyMatch(key -> SECRET_MARKERS.stream().anyMatch(key::contains))) {
			throw new IllegalArgumentException("secret-bearing observations are forbidden");
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
