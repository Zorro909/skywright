package de.zorro909.skywright.backend.targetstorage;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;

record TargetStorageConfiguration(URI endpoint, String region, boolean pathStyleAccess,
		Map<String, String> compatibilityOptions) {
	TargetStorageConfiguration {
		Objects.requireNonNull(endpoint, "endpoint");
		if (!List.of("http", "https").contains(endpoint.getScheme())) {
			throw new IllegalArgumentException("endpoint must use http or https");
		}
		if (region == null || region.isBlank()) {
			throw new IllegalArgumentException("region must not be blank");
		}
		if (!(compatibilityOptions = Map.copyOf(compatibilityOptions)).keySet()
			.stream()
			.allMatch(TargetStorageConfiguration::allowedOption)) {
			throw new IllegalArgumentException("unsupported compatibility option");
		}
	}

	private static boolean allowedOption(String option) {
		return List.of("chunkedEncoding", "checksumCalculation", "payloadSigning").contains(option);
	}
}
