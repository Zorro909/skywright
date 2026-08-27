package de.zorro909.skywright.backend.pricing;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class NonSecretDocument {

	private static final Set<String> SECRET_KEYS = Set.of("secret", "password", "token", "apikey", "api-key",
			"privatekey", "private-key", "credential", "credentials");

	private NonSecretDocument() {
	}

	static void requireSafe(Object value) {
		if (value instanceof Map<?, ?> map) {
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT).replace("_", "");
				if (SECRET_KEYS.contains(key) || key.contains("secret") || key.contains("password")
						|| key.contains("token")) {
					throw new PriceSourceValidationException("PRICE_SOURCE_SECRET_FORBIDDEN",
							"Price Source data must contain only non-secret values");
				}
				requireSafe(entry.getValue());
			}
		}
		else if (value instanceof Iterable<?> values) {
			values.forEach(NonSecretDocument::requireSafe);
		}
		else if (value instanceof String text && text.matches("(?i)https?://.*")) {
			try {
				URI uri = URI.create(text);
				if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
					throw new PriceSourceValidationException("PRICE_SOURCE_SECRET_FORBIDDEN",
							"Price Source data must contain only non-secret values");
				}
			}
			catch (IllegalArgumentException error) {
				throw new PriceSourceValidationException("PRICE_SOURCE_CONFIGURATION_INVALID",
						"Price Source data contains an invalid endpoint");
			}
		}
	}

}
