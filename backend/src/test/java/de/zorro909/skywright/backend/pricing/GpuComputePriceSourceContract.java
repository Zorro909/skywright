package de.zorro909.skywright.backend.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

abstract class GpuComputePriceSourceContract {

	static final UUID SOURCE_ID = UUID.randomUUID();

	static final UUID OFFERING_ID = UUID.randomUUID();

	static final Instant QUOTE_TIME = Instant.parse("2030-01-02T00:00:00Z");

	abstract GpuComputePriceSource sourceWithObservation(Instant observedAt);

	abstract GpuComputePriceSource missingSource();

	abstract GpuComputePriceSource unavailableSource();

	abstract BigDecimal expectedMinimumQuantity();

	abstract BigDecimal expectedBillingQuantum();

	@Test
	void preservesExactRateBillingSourceAndTimeFactsAtFreshnessBoundary() {
		GpuComputePriceResult result = sourceWithObservation(QUOTE_TIME.minus(Duration.ofHours(6)))
			.price(query(Duration.ofHours(6)));

		assertThat(result.outcome()).isEqualTo(GpuComputePriceResult.Outcome.AVAILABLE);
		assertThat(result.rate().sourceId()).isEqualTo(SOURCE_ID);
		assertThat(result.rate().sourceRevision()).isEqualTo(1);
		assertThat(result.rate().offeringId()).isEqualTo(OFFERING_ID);
		assertThat(result.rate().value()).isEqualByComparingTo("2.3400");
		assertThat(result.rate().minimumQuantity()).isEqualByComparingTo(expectedMinimumQuantity());
		assertThat(result.rate().billingQuantum()).isEqualByComparingTo(expectedBillingQuantum());
		assertThat(result.rate().nativeCurrency()).isEqualTo("USD");
		assertThat(result.rate().nativeUnit()).isEqualTo("instance-hour");
		assertThat(result.rate().provenance()).containsEntry("source", "contract fixture");
		assertThat(result.rate().observedAt()).isEqualTo(QUOTE_TIME.minus(Duration.ofHours(6)));
		assertThat(result.rate().effectiveFrom()).isEqualTo(Instant.parse("2030-01-01T00:00:00Z"));
		assertThat(result.rate().effectiveUntil()).isEqualTo(Instant.parse("2030-02-01T00:00:00Z"));
	}

	@Test
	void distinguishesStaleMissingAndUnavailableWithoutLeakingFailures() {
		GpuComputePriceResult stale = sourceWithObservation(QUOTE_TIME.minus(Duration.ofHours(6)).minusNanos(1))
			.price(query(Duration.ofHours(6)));
		GpuComputePriceResult missing = missingSource().price(query(Duration.ofHours(6)));
		GpuComputePriceResult future = sourceWithObservation(QUOTE_TIME.plusNanos(1)).price(query(Duration.ofHours(6)));
		GpuComputePriceResult unavailable = unavailableSource().price(query(Duration.ofHours(6)));

		assertThat(stale.outcome()).isEqualTo(GpuComputePriceResult.Outcome.STALE);
		assertThat(missing.code()).isEqualTo("GPU_COMPUTE_PRICE_MISSING");
		assertThat(future.code()).isEqualTo("GPU_COMPUTE_PRICE_MISSING");
		assertThat(unavailable.outcome()).isEqualTo(GpuComputePriceResult.Outcome.UNAVAILABLE);
		assertThat(unavailable.detail()).doesNotContain("password", "database");
	}

	static GpuComputePriceQuery query(Duration maximumAge) {
		return new GpuComputePriceQuery(SOURCE_ID, 1, OFFERING_ID, "aws", "us-east-1", "p5.48xlarge", "H100", 8, false,
				QUOTE_TIME, maximumAge);
	}

}
