package de.zorro909.skywright.backend.rundefinition;

import de.zorro909.skywright.backend.gpuoffering.EligibleGpuOfferingCatalogue;
import de.zorro909.skywright.backend.gpuoffering.EligibleGpuOfferingView;
import de.zorro909.skywright.backend.pricing.BoundGpuComputePrice;
import de.zorro909.skywright.backend.pricing.CurrencyConversionOutcome;
import de.zorro909.skywright.backend.pricing.CurrencyConversionQuote;
import de.zorro909.skywright.backend.pricing.GpuComputePriceResolver;
import de.zorro909.skywright.backend.pricing.GpuComputePriceResult;
import de.zorro909.skywright.backend.pricing.GpuComputeRate;
import de.zorro909.skywright.backend.pricing.PriceSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves native prices and required currency conversions from one transactionally
 * consistent snapshot.
 */
@Service
public class CostQuoteSnapshotReader implements CostQuoteReader {

	private final EligibleGpuOfferingCatalogue catalogue;

	private final GpuComputePriceResolver prices;

	private final PriceSource conversions;

	CostQuoteSnapshotReader(EligibleGpuOfferingCatalogue catalogue, GpuComputePriceResolver prices,
			PriceSource conversions) {
		this.catalogue = catalogue;
		this.prices = prices;
		this.conversions = conversions;
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
		Map<CurrencyPair, CurrencyConversionQuote> resolvedConversions = new HashMap<>();
		for (EligibleGpuOfferingView offering : offerings) {
			resolve(offering, reportingCurrency, quoteTime, resolvedConversions, candidates, failures);
		}
		return failures.isEmpty() ? new CostQuoteAssessment(candidates, List.of())
				: new CostQuoteAssessment(List.of(), failures.stream().distinct().sorted().toList());
	}

	private void resolve(EligibleGpuOfferingView offering, String reportingCurrency, Instant quoteTime,
			Map<CurrencyPair, CurrencyConversionQuote> resolvedConversions, List<CostQuoteCandidate> candidates,
			List<RunDefinitionFailure> failures) {
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
		CostQuoteConversion conversion = null;
		if (!reportingCurrency.equals(rate.nativeCurrency())) {
			CurrencyPair pair = new CurrencyPair(rate.nativeCurrency(), reportingCurrency);
			CurrencyConversionQuote resolved;
			try {
				resolved = resolvedConversions.computeIfAbsent(pair, ignored -> this.conversions
					.resolveCurrencyConversion(pair.nativeCurrency(), pair.reportingCurrency(), quoteTime));
			}
			catch (RuntimeException failure) {
				failures.add(conversionFailure("CURRENCY_CONVERSION_SOURCE_UNAVAILABLE", offering, rate,
						reportingCurrency, null));
				return;
			}
			if (resolved == null) {
				failures.add(conversionFailure("CURRENCY_CONVERSION_SOURCE_UNAVAILABLE", offering, rate,
						reportingCurrency, null));
				return;
			}
			if (resolved.outcome() != CurrencyConversionOutcome.QUALIFYING) {
				String code = switch (resolved.outcome()) {
					case UNAVAILABLE -> "CURRENCY_CONVERSION_SOURCE_UNAVAILABLE";
					case MISSING -> "CURRENCY_CONVERSION_MISSING";
					case STALE -> "CURRENCY_CONVERSION_STALE";
					case QUALIFYING -> throw new IllegalStateException("qualifying conversion handled above");
				};
				failures.add(conversionFailure(code, offering, rate, reportingCurrency, resolved));
				return;
			}
			conversion = conversion(resolved);
		}
		candidates.add(new CostQuoteCandidate(offering.id(), offering.revision(), offering.targetClass(),
				offering.target(), offering.providerOfferingId(), offering.region(), offering.instanceType(),
				offering.gpuModel(), offering.gpuCount(), offering.gpuMemoryBytes(),
				offering.purchaseMode().wireValue(), offering.supportTier().wireValue(), rate.value(),
				rate.nativeCurrency(), rate.nativeUnit(), rate.minimumQuantity(), rate.billingQuantum(),
				rate.provenance(), rate.sourceId(), rate.sourceRevision(), price.sourceKind(), rate.effectiveFrom(),
				rate.effectiveUntil(), rate.observedAt(), price.sourceObservedFrom(), price.sourceObservedUntil(),
				price.maximumObservationAge(), conversion));
	}

	private static CostQuoteConversion conversion(CurrencyConversionQuote value) {
		return new CostQuoteConversion(value.nativeCurrency(), value.reportingCurrency(), value.rate(),
				value.provenance(), value.sourceId(), value.sourceRevision(), value.sourceKind(), value.effectiveFrom(),
				value.effectiveUntil(), value.observedAt(), value.sourceObservedFrom(), value.sourceObservedUntil(),
				value.maximumObservationAge());
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

	private static RunDefinitionFailure conversionFailure(String code, EligibleGpuOfferingView offering,
			GpuComputeRate rate, String reportingCurrency, CurrencyConversionQuote conversion) {
		Map<String, String> details = new java.util.TreeMap<>();
		details.put("nativeCurrency", rate.nativeCurrency());
		details.put("offeringId", offering.id().toString());
		details.put("reportingCurrency", reportingCurrency);
		details.put("target", offering.target());
		if (conversion != null && conversion.sourceId() != null) {
			details.put("sourceId", conversion.sourceId().toString());
			details.put("sourceRevision", Long.toString(conversion.sourceRevision()));
		}
		return new RunDefinitionFailure(code, "price-source", "/costQuote/candidates/" + offering.id() + "/conversion",
				"complete", details);
	}

	private record CurrencyPair(String nativeCurrency, String reportingCurrency) {
	}

}
