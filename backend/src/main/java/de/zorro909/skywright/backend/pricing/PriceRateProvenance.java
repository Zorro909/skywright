package de.zorro909.skywright.backend.pricing;

import de.zorro909.skywright.backend.target.TargetIdentity;
import java.util.Map;
import java.util.Set;

final class PriceRateProvenance {

	private static final Set<String> KEYS = Set.of("source", "documentRevision", "valueKind", "skyPilotVersion",
			"catalogRequestId", "target", "region", "instanceType", "gpuModel", "gpuCount", "purchaseMode");

	private static final Set<String> TEXT_KEYS = Set.of("source", "documentRevision", "skyPilotVersion",
			"catalogRequestId", "region", "instanceType", "gpuModel");

	private PriceRateProvenance() {
	}

	static Map<String, Object> validate(Map<String, Object> value) {
		SecretFreeText.requireSafe(value);
		if (value == null || !KEYS.containsAll(value.keySet()) || !text(value.get("source"))) {
			throw new IllegalArgumentException("Price provenance is invalid");
		}
		for (String key : TEXT_KEYS) {
			if (value.containsKey(key) && !text(value.get(key))) {
				throw new IllegalArgumentException("Price provenance is invalid");
			}
		}
		if (value.containsKey("valueKind") && !"estimate".equals(value.get("valueKind"))
				|| value.containsKey("target")
						&& (!(value.get("target") instanceof String target) || !TargetIdentity.valid(target))
				|| value.containsKey("gpuCount") && (!(value.get("gpuCount") instanceof Number count)
						|| count.longValue() <= 0 || count.doubleValue() != count.longValue())
				|| value.containsKey("purchaseMode")
						&& !Set.of("on-demand", "spot").contains(value.get("purchaseMode"))) {
			throw new IllegalArgumentException("Price provenance is invalid");
		}
		return Map.copyOf(value);
	}

	private static boolean text(Object value) {
		return value instanceof String text && !text.isBlank() && text.length() <= 255;
	}

}
