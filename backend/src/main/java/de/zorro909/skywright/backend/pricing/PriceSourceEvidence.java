package de.zorro909.skywright.backend.pricing;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * The immutable Price Source revision, assessment interval, and freshness policy used by
 * a quote.
 */
public record PriceSourceEvidence(UUID sourceId, long sourceRevision, String sourceKind, Duration maximumObservationAge,
		Instant sourceObservedFrom, Instant sourceObservedUntil) {
}
