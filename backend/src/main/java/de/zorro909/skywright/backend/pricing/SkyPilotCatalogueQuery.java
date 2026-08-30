package de.zorro909.skywright.backend.pricing;

import java.time.Instant;

public record SkyPilotCatalogueQuery(String target, String region, String instanceType, String gpuModel, int gpuCount,
		boolean spot, Instant observedAt) {
}
