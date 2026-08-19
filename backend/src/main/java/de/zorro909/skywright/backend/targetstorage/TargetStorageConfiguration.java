package de.zorro909.skywright.backend.targetstorage;

import de.zorro909.skywright.backend.runstore.RunStoreS3Compatibility;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

record TargetStorageConfiguration(URI endpoint, String region, boolean pathStyleAccess,
		Map<String, String> compatibilityOptions) {
	TargetStorageConfiguration {
		Objects.requireNonNull(endpoint, "endpoint");
		if (endpoint.getScheme() == null
				|| !List.of("http", "https").contains(endpoint.getScheme().toLowerCase(java.util.Locale.ROOT))) {
			throw new IllegalArgumentException("endpoint must use http or https");
		}
		if (endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getQuery() != null
				|| endpoint.getFragment() != null) {
			throw new IllegalArgumentException("endpoint must not contain credentials, query, or fragment");
		}
		endpoint = TargetStorageConfiguration.normalizedEndpoint(endpoint);
		if (endpoint.toASCIIString().length() > 2048) {
			throw new IllegalArgumentException("endpoint must not exceed 2048 characters");
		}
		if (region == null || region.isBlank()) {
			throw new IllegalArgumentException("region must not be blank");
		}
		if (!(compatibilityOptions = Map.copyOf(compatibilityOptions)).keySet()
			.stream()
			.allMatch(TargetStorageConfiguration::allowedOption)) {
			throw new IllegalArgumentException("unsupported compatibility option");
		}
		RunStoreS3Compatibility.configuration(pathStyleAccess, compatibilityOptions);
		RunStoreS3Compatibility.checksumCalculation(compatibilityOptions);
	}

	private static boolean allowedOption(String option) {
		return List.of("chunkedEncoding", "checksumCalculation").contains(option);
	}

	private static URI normalizedEndpoint(URI endpoint) {
		String scheme = endpoint.getScheme().toLowerCase(java.util.Locale.ROOT);
		int port = endpoint.getPort();
		if (port == 80 && "http".equals(scheme) || port == 443 && "https".equals(scheme)) {
			port = -1;
		}
		String path = endpoint.getPath();
		try {
			return new URI(scheme, null, endpoint.getHost().toLowerCase(java.util.Locale.ROOT), port, path, null, null)
				.normalize();
		}
		catch (URISyntaxException impossible) {
			throw new IllegalArgumentException("endpoint is invalid", impossible);
		}
	}

	static String resourceKey(URI endpoint, String bucket) {
		URI normalized = TargetStorageConfiguration.normalizedEndpoint(endpoint);
		String endpointKey = normalized.toASCIIString();
		if (normalized.getPath().isEmpty()) {
			endpointKey += "/";
		}
		return endpointKey + "\n" + bucket;
	}
}
