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
			throw invalid("endpoint must use http or https");
		}
		if (endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getQuery() != null
				|| endpoint.getFragment() != null) {
			throw invalid("endpoint must name a host and must not contain user info, a query, or a fragment");
		}
		if (endpoint.toString().length() > 2048) {
			throw invalid("endpoint must not exceed 2048 characters");
		}
		if (region == null || region.isBlank()) {
			throw invalid("region must not be blank");
		}
		if (region.length() > 255) {
			throw invalid("region must not exceed 255 characters");
		}
		if (!(compatibilityOptions = Map.copyOf(compatibilityOptions)).keySet()
			.stream()
			.allMatch(TargetStorageConfiguration::allowedOption)) {
			throw invalid("unsupported compatibility option");
		}
		compatibilityOptions.forEach(TargetStorageConfiguration::validateOption);
	}

	private static boolean allowedOption(String option) {
		return List.of("chunkedEncoding", "checksumCalculation").contains(option);
	}

	private static void validateOption(String option, String value) {
		boolean valid = switch (option) {
			case "chunkedEncoding" -> List.of("enabled", "disabled").contains(value);
			case "checksumCalculation" -> List.of("when-required", "when-supported").contains(value);
			default -> false;
		};
		if (!valid) {
			throw invalid("unsupported value for compatibility option " + option);
		}
	}

	private static TargetStorageValidationException invalid(String detail) {
		return new TargetStorageValidationException("TARGET_STORAGE_CONFIGURATION_INVALID", detail);
	}
}
