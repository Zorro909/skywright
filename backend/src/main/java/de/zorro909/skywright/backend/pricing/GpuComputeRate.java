package de.zorro909.skywright.backend.pricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record GpuComputeRate(UUID sourceId, long sourceRevision, UUID offeringId, String nativeCurrency,
		String nativeUnit, BigDecimal value, BigDecimal minimumQuantity, BigDecimal billingQuantum,
		Map<String, Object> provenance, Instant observedAt, Instant effectiveFrom, Instant effectiveUntil) {
}
