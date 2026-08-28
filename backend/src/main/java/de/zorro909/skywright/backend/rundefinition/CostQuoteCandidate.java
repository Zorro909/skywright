package de.zorro909.skywright.backend.rundefinition;

import de.zorro909.skywright.backend.targetstorage.TargetClass;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Frozen offering, rate, billing, and source evidence used by one Cost Quote. */
public record CostQuoteCandidate(UUID offeringId, long offeringRevision, TargetClass targetClass, String target,
		String providerOfferingId, String region, String instanceType, String gpuModel, int gpuCount,
		long gpuMemoryBytes, String purchaseMode, String supportTier, BigDecimal nativeRate, String nativeCurrency,
		String nativeUnit, BigDecimal minimumQuantity, BigDecimal billingQuantum, Map<String, Object> provenance,
		UUID sourceId, long sourceRevision, String sourceKind, Instant effectiveFrom, Instant effectiveUntil,
		Instant rateObservedAt, Instant sourceObservedFrom, Instant sourceObservedUntil,
		Duration maximumObservationAge) {

	public CostQuoteCandidate {
		provenance = immutableObject(provenance);
	}

	private static Map<String, Object> immutableObject(Map<String, Object> source) {
		Map<String, Object> copy = new LinkedHashMap<>();
		source.forEach((key, value) -> copy.put(key, immutableValue(value)));
		return Collections.unmodifiableMap(copy);
	}

	private static Object immutableValue(Object value) {
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> copy = new LinkedHashMap<>();
			map.forEach((key, nested) -> copy.put((String) key, immutableValue(nested)));
			return Collections.unmodifiableMap(copy);
		}
		if (value instanceof List<?> list) {
			List<Object> copy = new ArrayList<>(list.size());
			list.forEach(nested -> copy.add(immutableValue(nested)));
			return Collections.unmodifiableList(copy);
		}
		return value;
	}

}
