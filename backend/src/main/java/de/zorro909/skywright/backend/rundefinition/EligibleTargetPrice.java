package de.zorro909.skywright.backend.rundefinition;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Immutable price and conversion evidence for one eligible GPU offering. */
public record EligibleTargetPrice(BigDecimal nativeHourlyRate, String nativeCurrency, BigDecimal minimumQuantity,
		BigDecimal billingQuantum, UUID sourceId, long sourceRevision, String sourceKind, Instant effectiveFrom,
		Instant effectiveUntil, Instant observedFrom, Instant observedUntil, BigDecimal conversionRate,
		UUID conversionSourceId, long conversionSourceRevision, String conversionSourceKind,
		Instant conversionObservedAt, Duration maximumObservationAge) {
}
