package de.zorro909.skywright.backend.pricing;

import java.net.URI;
import java.util.Map;

final class SecretFreeText {

	private SecretFreeText() {
	}

	static void requireSafe(Object value) {
		if (value instanceof Map<?, ?> map) {
			map.values().forEach(SecretFreeText::requireSafe);
		}
		else if (value instanceof Iterable<?> values) {
			values.forEach(SecretFreeText::requireSafe);
		}
		else if (value instanceof String text && text.matches("(?i)[a-z][a-z0-9+.-]*://.*")) {
			URI uri;
			try {
				uri = URI.create(text);
			}
			catch (IllegalArgumentException failure) {
				throw new IllegalArgumentException("Non-secret text contains an invalid URI", failure);
			}
			if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
				throw new IllegalArgumentException("Non-secret text contains credential-bearing URI components");
			}
		}
	}

}
