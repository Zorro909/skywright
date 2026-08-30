package de.zorro909.skywright.backend.pricing;

import de.zorro909.skywright.backend.target.TargetIdentity;
import java.math.BigDecimal;
import java.net.URI;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class PriceSourceConfiguration {

	private static final Set<String> OPERATOR_KEYS = Set.of("capabilities", "nativeCurrencies", "nativeUnits", "rates");

	private static final Set<String> SKYPILOT_KEYS = Set.of("capabilities", "nativeCurrencies", "nativeUnits",
			"targets");

	private static final Set<String> PROVIDER_KEYS = Set.of("capabilities", "endpoint");

	private PriceSourceConfiguration() {
	}

	static Map<String, Object> validate(PriceSourceKind kind, Map<String, Object> value) {
		if (kind == null || value == null || value.isEmpty()) {
			throw invalid();
		}
		try {
			SecretFreeText.requireSafe(value);
		}
		catch (IllegalArgumentException failure) {
			throw invalid();
		}
		Set<String> allowed = switch (kind) {
			case OPERATOR_SCHEDULE -> OPERATOR_KEYS;
			case SKYPILOT_CATALOG -> SKYPILOT_KEYS;
			case PROVIDER_API -> PROVIDER_KEYS;
		};
		if (!allowed.containsAll(value.keySet()) || !strings(value.get("capabilities"))) {
			throw invalid();
		}
		switch (kind) {
			case OPERATOR_SCHEDULE -> validateOperator(value);
			case SKYPILOT_CATALOG -> validateSkyPilot(value);
			case PROVIDER_API -> validateProvider(value);
		}
		return Map.copyOf(value);
	}

	private static void validateOperator(Map<String, Object> value) {
		if (value.containsKey("nativeCurrencies") && !currencies(value.get("nativeCurrencies"))
				|| value.containsKey("nativeUnits") && !units(value.get("nativeUnits"))
				|| value.containsKey("rates") && !rates(value.get("rates"))) {
			throw invalid();
		}
	}

	private static void validateSkyPilot(Map<String, Object> value) {
		if (!currencies(value.get("nativeCurrencies")) || !units(value.get("nativeUnits"))
				|| !(value.get("targets") instanceof List<?> targets) || targets.isEmpty()
				|| !targets.stream().allMatch(String.class::isInstance)
				|| !targets.stream().map(String.class::cast).allMatch(TargetIdentity::valid)) {
			throw invalid();
		}
	}

	private static void validateProvider(Map<String, Object> value) {
		if (!(value.get("endpoint") instanceof String endpoint)) {
			throw invalid();
		}
		try {
			URI uri = URI.create(endpoint);
			if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
					|| uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
					|| uri.getFragment() != null || endpoint.length() > 2048) {
				throw invalid();
			}
		}
		catch (IllegalArgumentException failure) {
			throw invalid();
		}
	}

	private static boolean strings(Object value) {
		return value instanceof List<?> values && !values.isEmpty() && values.stream()
			.allMatch(item -> item instanceof String text && !text.isBlank() && text.length() <= 255);
	}

	private static boolean currencies(Object value) {
		if (!(value instanceof List<?> values) || values.isEmpty()) {
			return false;
		}
		try {
			return values.stream()
				.allMatch(item -> item instanceof String currency && currency.matches("[A-Z]{3}")
						&& Currency.getInstance(currency).getCurrencyCode().equals(currency));
		}
		catch (IllegalArgumentException failure) {
			return false;
		}
	}

	private static boolean units(Object value) {
		return value instanceof List<?> values && !values.isEmpty()
				&& values.stream().allMatch("instance-hour"::equals);
	}

	private static boolean rates(Object value) {
		return value instanceof List<?> values && !values.isEmpty() && values.stream().allMatch(item -> {
			if (!(item instanceof Map<?, ?> rate) || !rate.keySet().equals(Set.of("amount", "currency"))) {
				return false;
			}
			try {
				BigDecimal amount = new BigDecimal(rate.get("amount").toString());
				String currency = (String) rate.get("currency");
				return amount.signum() >= 0 && currency.matches("[A-Z]{3}")
						&& Currency.getInstance(currency).getCurrencyCode().equals(currency);
			}
			catch (IllegalArgumentException | ClassCastException | NullPointerException failure) {
				return false;
			}
		});
	}

	private static PriceSourceValidationException invalid() {
		return new PriceSourceValidationException("PRICE_SOURCE_CONFIGURATION_INVALID",
				"Configuration does not match the selected Price Source kind");
	}

}
