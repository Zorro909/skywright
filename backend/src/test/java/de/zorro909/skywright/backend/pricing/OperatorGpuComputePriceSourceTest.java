package de.zorro909.skywright.backend.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class OperatorGpuComputePriceSourceTest {

	private static final UUID SOURCE_ID = UUID.randomUUID();

	private static final UUID OFFERING_ID = UUID.randomUUID();

	private static final Instant QUOTE_TIME = Instant.parse("2030-01-02T00:00:00Z");

	@Test
	void preservesExactRateAndBillingRulesAtFreshnessBoundary() {
		var source = new OperatorGpuComputePriceSource(
				query -> Optional.of(entry(QUOTE_TIME.minus(Duration.ofHours(6)))));

		GpuComputePriceResult result = source.price(query(Duration.ofHours(6)));

		assertThat(result.outcome()).isEqualTo(GpuComputePriceResult.Outcome.AVAILABLE);
		assertThat(result.rate().value()).isEqualByComparingTo("2.3400");
		assertThat(result.rate().minimumQuantity()).isEqualByComparingTo("0.250");
		assertThat(result.rate().billingQuantum()).isEqualByComparingTo("0.016666666666666666");
		assertThat(result.rate().nativeCurrency()).isEqualTo("USD");
		assertThat(result.rate().nativeUnit()).isEqualTo("instance-hour");
		assertThat(result.rate().provenance()).containsEntry("source", "operator tariff");
	}

	@Test
	void distinguishesStaleMissingAndUnavailableWithoutLeakingFailures() {
		var stale = new OperatorGpuComputePriceSource(
				query -> Optional.of(entry(QUOTE_TIME.minus(Duration.ofHours(6)).minusNanos(1))));
		var missing = new OperatorGpuComputePriceSource(query -> Optional.empty());
		var unavailable = new OperatorGpuComputePriceSource(query -> {
			throw new IllegalStateException("database password must not escape");
		});

		assertThat(stale.price(query(Duration.ofHours(6))).outcome()).isEqualTo(GpuComputePriceResult.Outcome.STALE);
		assertThat(missing.price(query(Duration.ofHours(6))).code()).isEqualTo("GPU_COMPUTE_PRICE_MISSING");
		GpuComputePriceResult unavailableResult = unavailable.price(query(Duration.ofHours(6)));
		assertThat(unavailableResult.outcome()).isEqualTo(GpuComputePriceResult.Outcome.UNAVAILABLE);
		assertThat(unavailableResult.detail()).doesNotContain("password", "database");
	}

	private static GpuComputePriceQuery query(Duration maximumAge) {
		return new GpuComputePriceQuery(SOURCE_ID, 1, OFFERING_ID, QUOTE_TIME, maximumAge);
	}

	private static GpuPriceScheduleEntryView entry(Instant observedAt) {
		return new GpuPriceScheduleEntryView(UUID.randomUUID(), 1, SOURCE_ID, 1, OFFERING_ID, "USD", "instance-hour",
				new BigDecimal("2.3400"), new BigDecimal("0.250"), new BigDecimal("0.016666666666666666"),
				Map.of("source", "operator tariff"), observedAt, Instant.parse("2030-01-01T00:00:00Z"),
				Instant.parse("2030-02-01T00:00:00Z"));
	}

}
