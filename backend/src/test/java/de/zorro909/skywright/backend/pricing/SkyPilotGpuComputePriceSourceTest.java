package de.zorro909.skywright.backend.pricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

final class SkyPilotGpuComputePriceSourceTest extends GpuComputePriceSourceContract {

	@Override
	GpuComputePriceSource sourceWithObservation(Instant observedAt) {
		return new SkyPilotGpuComputePriceSource(query -> Optional.of(observation(observedAt)));
	}

	@Override
	GpuComputePriceSource missingSource() {
		return new SkyPilotGpuComputePriceSource(query -> Optional.empty());
	}

	@Override
	GpuComputePriceSource unavailableSource() {
		return new SkyPilotGpuComputePriceSource(query -> {
			throw new IllegalStateException("catalog database password must not escape");
		});
	}

	@Override
	BigDecimal expectedMinimumQuantity() {
		return new BigDecimal("0.250");
	}

	@Override
	BigDecimal expectedBillingQuantum() {
		return new BigDecimal("0.016666666666666666");
	}

	@Override
	String expectedNativeCurrency() {
		return "EUR";
	}

	private static SkyPilotCatalogueObservation observation(Instant observedAt) {
		return new SkyPilotCatalogueObservation(new BigDecimal("2.3400"), "EUR", "instance-hour",
				new BigDecimal("0.250"), new BigDecimal("0.016666666666666666"), Map.of("source", "contract fixture"),
				observedAt, Instant.parse("2030-01-01T00:00:00Z"), Instant.parse("2030-02-01T00:00:00Z"));
	}

}
