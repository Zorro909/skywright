package de.zorro909.skywright.backend.pricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class OperatorGpuComputePriceSourceTest extends GpuComputePriceSourceContract {

	@Override
	GpuComputePriceSource sourceWithObservation(Instant observedAt) {
		return new OperatorGpuComputePriceSource(query -> Optional.of(entry(observedAt)));
	}

	@Override
	GpuComputePriceSource missingSource() {
		return new OperatorGpuComputePriceSource(query -> Optional.empty());
	}

	@Override
	GpuComputePriceSource unavailableSource() {
		return new OperatorGpuComputePriceSource(query -> {
			throw new IllegalStateException("database password must not escape");
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

	private static GpuPriceScheduleEntryView entry(Instant observedAt) {
		return new GpuPriceScheduleEntryView(UUID.randomUUID(), 1, SOURCE_ID, 1, OFFERING_ID, "USD", "instance-hour",
				new BigDecimal("2.3400"), new BigDecimal("0.250"), new BigDecimal("0.016666666666666666"),
				Map.of("source", "contract fixture"), observedAt, Instant.parse("2030-01-01T00:00:00Z"),
				Instant.parse("2030-02-01T00:00:00Z"));
	}

}
