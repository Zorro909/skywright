package de.zorro909.skywright.backend.pricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record SkyPilotCatalogueObservation(BigDecimal hourlyRate, Map<String, Object> provenance, Instant observedAt,
		Instant effectiveFrom, Instant effectiveUntil) {
}
