package de.zorro909.skywright.backend.rundefinition;

import de.zorro909.skywright.backend.targetstorage.TargetClass;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
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
		provenance = Map.copyOf(provenance);
	}

}
