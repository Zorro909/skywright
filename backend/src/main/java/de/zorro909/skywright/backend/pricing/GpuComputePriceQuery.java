package de.zorro909.skywright.backend.pricing;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public record GpuComputePriceQuery(UUID sourceId, long sourceRevision, UUID offeringId, Instant quoteTime,
		Duration maximumObservationAge) {
}
