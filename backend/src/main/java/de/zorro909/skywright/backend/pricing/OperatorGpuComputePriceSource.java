package de.zorro909.skywright.backend.pricing;

import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class OperatorGpuComputePriceSource implements GpuComputePriceSource {

	private final GpuPriceScheduleReader schedule;

	OperatorGpuComputePriceSource(GpuPriceScheduleReader schedule) {
		this.schedule = schedule;
	}

	@Override
	public GpuComputePriceResult price(GpuComputePriceQuery query) {
		if (query == null || query.sourceId() == null || query.sourceRevision() <= 0 || query.offeringId() == null
				|| query.quoteTime() == null || query.maximumObservationAge() == null
				|| query.maximumObservationAge().isNegative() || query.maximumObservationAge().isZero()) {
			return GpuComputePriceResult.unavailable();
		}
		try {
			return this.schedule.rate(query)
				.map(entry -> result(query, entry))
				.orElseGet(GpuComputePriceResult::missing);
		}
		catch (RuntimeException failure) {
			return GpuComputePriceResult.unavailable();
		}
	}

	private static GpuComputePriceResult result(GpuComputePriceQuery query, GpuPriceScheduleEntryView entry) {
		GpuComputeRate rate = new GpuComputeRate(entry.sourceId(), entry.sourceRevision(), entry.offeringId(),
				entry.nativeCurrency(), entry.nativeUnit(), entry.value(), entry.minimumQuantity(),
				entry.billingQuantum(), entry.provenance(), entry.observedAt(), entry.effectiveFrom(),
				entry.effectiveUntil());
		Instant oldestAcceptedObservation = query.quoteTime().minus(query.maximumObservationAge());
		return entry.observedAt().isBefore(oldestAcceptedObservation) ? GpuComputePriceResult.stale(rate)
				: GpuComputePriceResult.available(rate);
	}

}
