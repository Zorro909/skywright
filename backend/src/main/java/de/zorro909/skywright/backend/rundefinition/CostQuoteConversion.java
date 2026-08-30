package de.zorro909.skywright.backend.rundefinition;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Frozen currency-pair conversion and Price Source evidence used by one Cost Quote
 * candidate.
 */
public record CostQuoteConversion(String nativeCurrency, String reportingCurrency, BigDecimal rate, String provenance,
		UUID sourceId, long sourceRevision, long scheduleRevision, String sourceKind, Instant effectiveFrom,
		Instant effectiveUntil, Instant observedAt, Instant sourceObservedFrom, Instant sourceObservedUntil,
		Duration maximumObservationAge) {
}
