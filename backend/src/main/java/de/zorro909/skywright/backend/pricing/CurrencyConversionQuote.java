package de.zorro909.skywright.backend.pricing;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public record CurrencyConversionQuote(CurrencyConversionOutcome outcome, String nativeCurrency,
		String reportingCurrency, BigDecimal rate, String provenance, Instant observedAt, Instant effectiveFrom,
		Instant effectiveUntil, UUID sourceId, Long sourceRevision, String sourceKind, Duration maximumObservationAge,
		Instant sourceObservedFrom, Instant sourceObservedUntil) {

	static CurrencyConversionQuote withoutConversion(CurrencyConversionOutcome outcome, String nativeCurrency,
			String reportingCurrency, UUID sourceId, Long sourceRevision, String sourceKind,
			Duration maximumObservationAge, Instant sourceObservedFrom, Instant sourceObservedUntil) {
		return new CurrencyConversionQuote(outcome, nativeCurrency, reportingCurrency, null, null, null, null, null,
				sourceId, sourceRevision, sourceKind, maximumObservationAge, sourceObservedFrom, sourceObservedUntil);
	}

}
