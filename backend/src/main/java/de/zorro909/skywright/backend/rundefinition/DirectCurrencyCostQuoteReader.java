package de.zorro909.skywright.backend.rundefinition;

import de.zorro909.skywright.backend.gpuoffering.EligibleGpuOfferingCatalogue;
import de.zorro909.skywright.backend.gpuoffering.EligibleGpuOfferingView;
import de.zorro909.skywright.backend.pricing.BoundGpuComputePrice;
import de.zorro909.skywright.backend.pricing.GpuComputePriceResolver;
import de.zorro909.skywright.backend.pricing.GpuComputePriceResult;
import de.zorro909.skywright.backend.pricing.GpuComputeRate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves direct Reporting Currency prices from one transactionally consistent snapshot.
 */
@Service
public class DirectCurrencyCostQuoteReader implements CostQuoteReader {

	private final EligibleGpuOfferingCatalogue catalogue;

	private final GpuComputePriceResolver prices;

	DirectCurrencyCostQuoteReader(EligibleGpuOfferingCatalogue catalogue, GpuComputePriceResolver prices) {
		this.catalogue = catalogue;
		this.prices = prices;
	}

	@Override
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public CostQuoteAssessment resolve(TargetRequest request, String reportingCurrency, Instant quoteTime) {
		List<EligibleGpuOfferingView> offerings;
		try {
			offerings = this.catalogue.eligible(request);
		}
		catch (RuntimeException failure) {
			return failed(new RunDefinitionFailure("GPU_OFFERING_CATALOGUE_UNAVAILABLE", "gpu-offering-catalogue",
					"/costQuote/candidates", "available"));
		}
		if (offerings.isEmpty()) {
			return failed(new RunDefinitionFailure("GPU_OFFERING_NONE_ELIGIBLE", "gpu-offering-catalogue",
					"/targetRequest", "eligible"));
		}

		List<CostQuoteCandidate> candidates = new ArrayList<>();
		List<RunDefinitionFailure> failures = new ArrayList<>();
		for (EligibleGpuOfferingView offering : offerings) {
			resolve(offering, reportingCurrency, quoteTime, candidates, failures);
		}
		return failures.isEmpty() ? new CostQuoteAssessment(candidates, List.of())
				: new CostQuoteAssessment(List.of(), failures.stream().distinct().sorted().toList());
	}

	private void resolve(EligibleGpuOfferingView offering, String reportingCurrency, Instant quoteTime,
			List<CostQuoteCandidate> candidates, List<RunDefinitionFailure> failures) {
		BoundGpuComputePrice price;
		try {
			price = this.prices.resolve(offering.target(), offering.id(), quoteTime);
		}
		catch (RuntimeException failure) {
			failures.add(failure("PRICE_SOURCE_UNAVAILABLE", offering, null));
			return;
		}
		if (price.result().outcome() != GpuComputePriceResult.Outcome.AVAILABLE) {
			failures.add(failure(price.result().code(), offering, price));
			return;
		}
		GpuComputeRate rate = price.result().rate();
		if (!reportingCurrency.equals(rate.nativeCurrency())) {
			failures.add(failure("GPU_COMPUTE_PRICE_CURRENCY_MISMATCH", offering, price));
			return;
		}
		candidates.add(new CostQuoteCandidate(offering.id(), offering.revision(), offering.targetClass(),
				offering.target(), offering.providerOfferingId(), offering.region(), offering.instanceType(),
				offering.gpuModel(), offering.gpuCount(), offering.gpuMemoryBytes(),
				offering.purchaseMode().wireValue(), offering.supportTier().wireValue(), rate.value(),
				rate.nativeCurrency(), rate.nativeUnit(), rate.minimumQuantity(), rate.billingQuantum(),
				rate.provenance(), rate.sourceId(), rate.sourceRevision(), price.sourceKind(), rate.effectiveFrom(),
				rate.effectiveUntil(), rate.observedAt(), price.sourceObservedFrom(), price.sourceObservedUntil(),
				price.maximumObservationAge()));
	}

	private static CostQuoteAssessment failed(RunDefinitionFailure failure) {
		return new CostQuoteAssessment(List.of(), List.of(failure));
	}

	private static RunDefinitionFailure failure(String code, EligibleGpuOfferingView offering,
			BoundGpuComputePrice price) {
		Map<String, String> details = new java.util.TreeMap<>();
		details.put("offeringId", offering.id().toString());
		details.put("target", offering.target());
		if (price != null && price.sourceId() != null) {
			details.put("sourceId", price.sourceId().toString());
			details.put("sourceRevision", Long.toString(price.sourceRevision()));
		}
		return new RunDefinitionFailure(code, "price-source", "/costQuote/candidates/" + offering.id(), "complete",
				details);
	}

}
