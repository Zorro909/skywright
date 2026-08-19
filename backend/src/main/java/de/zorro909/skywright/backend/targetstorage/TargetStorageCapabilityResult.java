package de.zorro909.skywright.backend.targetstorage;

import java.util.List;
import java.util.Locale;
import java.util.Map;

record TargetStorageCapabilityResult(String capability, boolean succeeded, String failureCode, String summary,
		Map<String, String> observations) {

	private static final List<String> SECRET_MARKERS = List.of("secretaccesskey", "password=", "token=",
			"authorization:", "accesskeyid", "xamzsignature");

	TargetStorageCapabilityResult {
		if (capability == null || capability.isBlank()) {
			throw new IllegalArgumentException("capability must not be blank");
		}
		observations = Map.copyOf(observations);
		if (TargetStorageCapabilityResult.containsSecretMarker(capability)
				|| TargetStorageCapabilityResult.containsSecretMarker(failureCode)
				|| TargetStorageCapabilityResult.containsSecretMarker(summary)
				|| observations.entrySet()
					.stream()
					.anyMatch(entry -> TargetStorageCapabilityResult.containsSecretMarker(entry.getKey())
							|| TargetStorageCapabilityResult.containsSecretMarker(entry.getValue()))) {
			throw new IllegalArgumentException("secret-bearing observations are forbidden");
		}
		if (succeeded && (failureCode != null || summary != null)) {
			throw new IllegalArgumentException("successful capability cannot carry a failure");
		}
		if (!(succeeded || failureCode != null && summary != null)) {
			throw new IllegalArgumentException("failed capability requires a code and summary");
		}
	}

	private static boolean containsSecretMarker(String value) {
		if (value == null) {
			return false;
		}
		String normalized = value.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
		return SECRET_MARKERS.stream().anyMatch(normalized::contains);
	}

	static TargetStorageCapabilityResult success(String capability) {
		return new TargetStorageCapabilityResult(capability, true, null, null, Map.of());
	}

	static TargetStorageCapabilityResult failure(String capability, String code, String summary,
			Map<String, String> observations) {
		return new TargetStorageCapabilityResult(capability, false, code, summary, observations);
	}
}
