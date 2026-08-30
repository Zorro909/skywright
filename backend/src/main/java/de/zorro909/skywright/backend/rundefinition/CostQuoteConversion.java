package de.zorro909.skywright.backend.rundefinition;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import de.zorro909.skywright.backend.pricing.PriceSourceEvidence;

/**
 * Frozen currency-pair conversion and Price Source evidence used by one Cost Quote
 * candidate.
 */
public record CostQuoteConversion(String nativeCurrency, String reportingCurrency, BigDecimal rate,
		Map<String, Object> provenance, long scheduleRevision, PriceSourceEvidence evidence, Instant effectiveFrom,
		Instant effectiveUntil, Instant observedAt) {

	public CostQuoteConversion(String nativeCurrency, String reportingCurrency, BigDecimal rate,
			Map<String, Object> provenance, java.util.UUID sourceId, long sourceRevision, long scheduleRevision,
			String sourceKind, Instant effectiveFrom, Instant effectiveUntil, Instant observedAt,
			Instant sourceObservedFrom, Instant sourceObservedUntil, java.time.Duration maximumObservationAge) {
		this(nativeCurrency, reportingCurrency, rate, provenance, scheduleRevision, new PriceSourceEvidence(sourceId,
				sourceRevision, sourceKind, maximumObservationAge, sourceObservedFrom, sourceObservedUntil),
				effectiveFrom, effectiveUntil, observedAt);
	}

	public java.util.UUID sourceId() {
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

}
