package de.zorro909.skywright.backend.rundefinition;

import de.zorro909.skywright.backend.targetstorage.TargetClass;
import de.zorro909.skywright.backend.pricing.PriceSourceEvidence;
import java.math.BigDecimal;
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
		PriceSourceEvidence evidence, Instant effectiveFrom, Instant effectiveUntil, Instant rateObservedAt,
		CostQuoteConversion conversion) {

	public CostQuoteCandidate(UUID offeringId, long offeringRevision, TargetClass targetClass, String target,
			String providerOfferingId, String region, String instanceType, String gpuModel, int gpuCount,
			long gpuMemoryBytes, String purchaseMode, String supportTier, BigDecimal nativeRate, String nativeCurrency,
			String nativeUnit, BigDecimal minimumQuantity, BigDecimal billingQuantum, Map<String, Object> provenance,
			UUID sourceId, long sourceRevision, String sourceKind, Instant effectiveFrom, Instant effectiveUntil,
			Instant rateObservedAt, Instant sourceObservedFrom, Instant sourceObservedUntil,
			java.time.Duration maximumObservationAge, CostQuoteConversion conversion) {
		this(offeringId, offeringRevision, targetClass, target, providerOfferingId, region, instanceType, gpuModel,
				gpuCount, gpuMemoryBytes, purchaseMode, supportTier, nativeRate, nativeCurrency, nativeUnit,
				minimumQuantity, billingQuantum, provenance, new PriceSourceEvidence(sourceId, sourceRevision,
						sourceKind, maximumObservationAge, sourceObservedFrom, sourceObservedUntil),
				effectiveFrom, effectiveUntil, rateObservedAt, conversion);
	}

	public CostQuoteCandidate {
		provenance = immutableObject(provenance);
	}

	public UUID sourceId() {
		return this.evidence.sourceId();
	}

	public long sourceRevision() {
		return this.evidence.sourceRevision();
	}

	public String sourceKind() {
		return this.evidence.sourceKind();
	}

	public Instant sourceObservedFrom() {
		return this.evidence.sourceObservedFrom();
	}

	public Instant sourceObservedUntil() {
		return this.evidence.sourceObservedUntil();
	}

	public java.time.Duration maximumObservationAge() {
		return this.evidence.maximumObservationAge();
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
