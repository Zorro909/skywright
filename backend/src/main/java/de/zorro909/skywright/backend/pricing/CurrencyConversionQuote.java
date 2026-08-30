package de.zorro909.skywright.backend.pricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record CurrencyConversionQuote(CurrencyConversionOutcome outcome, String nativeCurrency,
		String reportingCurrency, BigDecimal rate, Map<String, Object> provenance, Instant observedAt,
		Instant effectiveFrom, Instant effectiveUntil, Long scheduleRevision, PriceSourceEvidence evidence) {

	public CurrencyConversionQuote(CurrencyConversionOutcome outcome, String nativeCurrency, String reportingCurrency,
			BigDecimal rate, Map<String, Object> provenance, Instant observedAt, Instant effectiveFrom,
			Instant effectiveUntil, UUID sourceId, Long sourceRevision, Long scheduleRevision, String sourceKind,
			java.time.Duration maximumObservationAge, Instant sourceObservedFrom, Instant sourceObservedUntil) {
		this(outcome, nativeCurrency, reportingCurrency, rate, provenance, observedAt, effectiveFrom, effectiveUntil,
				scheduleRevision, new PriceSourceEvidence(sourceId, sourceRevision == null ? 0 : sourceRevision,
						sourceKind, maximumObservationAge, sourceObservedFrom, sourceObservedUntil));
	}

	static CurrencyConversionQuote withoutConversion(CurrencyConversionOutcome outcome, String nativeCurrency,
			String reportingCurrency, UUID sourceId, Long sourceRevision, Long scheduleRevision, String sourceKind,
			java.time.Duration maximumObservationAge, Instant sourceObservedFrom, Instant sourceObservedUntil) {
		return new CurrencyConversionQuote(outcome, nativeCurrency, reportingCurrency, null, null, null, null, null,
				scheduleRevision, new PriceSourceEvidence(sourceId, sourceRevision == null ? 0 : sourceRevision,
						sourceKind, maximumObservationAge, sourceObservedFrom, sourceObservedUntil));
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

	public java.time.Duration maximumObservationAge() {
		return this.evidence.maximumObservationAge();
	}

	public Instant sourceObservedFrom() {
		return this.evidence.sourceObservedFrom();
	}

	public Instant sourceObservedUntil() {
		return this.evidence.sourceObservedUntil();
	}

}
