package de.zorro909.skywright.backend.pricing;

import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class SkyPilotGpuComputePriceSource implements GpuComputePriceSource {

	private final SkyPilotCatalogue catalogue;

	SkyPilotGpuComputePriceSource(SkyPilotCatalogue catalogue) {
		this.catalogue = catalogue;
	}

	@Override
	public GpuComputePriceResult price(GpuComputePriceQuery query) {
		if (!valid(query)) {
			return GpuComputePriceResult.unavailable();
		}
		try {
			return this.catalogue.price(catalogueQuery(query))
				.map(observation -> result(query, observation))
				.orElseGet(GpuComputePriceResult::missing);
		}
		catch (Exception failure) {
			return GpuComputePriceResult.unavailable();
		}
	}

	private static boolean valid(GpuComputePriceQuery query) {
		return query != null && query.sourceId() != null && query.sourceRevision() > 0 && query.offeringId() != null
				&& text(query.target()) && text(query.region()) && text(query.instanceType()) && text(query.gpuModel())
				&& query.gpuCount() > 0 && query.quoteTime() != null && query.maximumObservationAge() != null
				&& !query.maximumObservationAge().isNegative() && !query.maximumObservationAge().isZero();
	}

	private static boolean text(String value) {
		return value != null && !value.isBlank();
	}

	private static SkyPilotCatalogueQuery catalogueQuery(GpuComputePriceQuery query) {
		return new SkyPilotCatalogueQuery(query.target(), query.region(), query.instanceType(), query.gpuModel(),
				query.gpuCount(), query.spot(), query.quoteTime());
	}

	private static GpuComputePriceResult result(GpuComputePriceQuery query, SkyPilotCatalogueObservation observation) {
		if (observation.hourlyRate() == null || observation.hourlyRate().signum() < 0
				|| observation.provenance() == null || observation.observedAt() == null
				|| observation.effectiveFrom() == null || observation.observedAt().isAfter(query.quoteTime())
				|| observation.effectiveFrom().isAfter(query.quoteTime())
				|| observation.effectiveUntil() != null && !query.quoteTime().isBefore(observation.effectiveUntil())) {
			return GpuComputePriceResult.missing();
		}
		GpuComputeRate rate = new GpuComputeRate(query.sourceId(), query.sourceRevision(), query.offeringId(), "USD",
				"instance-hour", observation.hourlyRate(), BigDecimal.ONE, BigDecimal.ONE, observation.provenance(),
				observation.observedAt(), observation.effectiveFrom(), observation.effectiveUntil());
		Instant oldestAcceptedObservation = query.quoteTime().minus(query.maximumObservationAge());
		return observation.observedAt().isBefore(oldestAcceptedObservation) ? GpuComputePriceResult.stale(rate)
				: GpuComputePriceResult.available(rate);
	}

}
