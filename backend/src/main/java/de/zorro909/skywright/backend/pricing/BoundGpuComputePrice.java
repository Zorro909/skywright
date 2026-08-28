package de.zorro909.skywright.backend.pricing;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Price-source outcome plus the exact binding and assessment evidence used to obtain it.
 */
public record BoundGpuComputePrice(GpuComputePriceResult result, UUID sourceId, long sourceRevision, String sourceKind,
		Duration maximumObservationAge, Instant sourceObservedFrom, Instant sourceObservedUntil) {
}
