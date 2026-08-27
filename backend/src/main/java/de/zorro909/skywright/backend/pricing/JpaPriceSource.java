package de.zorro909.skywright.backend.pricing;

import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class JpaPriceSource implements PriceSource {

	private final EntityManager entities;

	JpaPriceSource(EntityManager entities) {
		this.entities = entities;
	}

	@Override
	public CurrencyConversionQuote resolveCurrencyConversion(String nativeCurrency, String reportingCurrency,
			Instant quoteTime) {
		String bindingKey = "currency:" + nativeCurrency + ":" + reportingCurrency;
		PriceSourceBindingEntity binding = this.entities.find(PriceSourceBindingEntity.class, bindingKey);
		if (binding == null) {
			return CurrencyConversionQuote.withoutConversion(CurrencyConversionOutcome.UNAVAILABLE, nativeCurrency,
					reportingCurrency, null, null);
		}
		PriceSourceEntity source = this.entities.find(PriceSourceEntity.class, binding.sourceId);
		if (source == null || source.activeRevision == null || source.activeRevision != binding.sourceRevision
				|| !assessedForPair(source, binding.sourceRevision, bindingKey)) {
			return CurrencyConversionQuote.withoutConversion(CurrencyConversionOutcome.UNAVAILABLE, nativeCurrency,
					reportingCurrency, binding.sourceId, binding.sourceRevision);
		}
		List<CurrencyConversionEntity> matches = this.entities.createQuery("""
				select conversion from CurrencyConversionEntity conversion
				where conversion.sourceId = :sourceId
				  and conversion.nativeCurrency = :nativeCurrency
				  and conversion.reportingCurrency = :reportingCurrency
				  and conversion.effectiveFrom <= :quoteTime
				  and conversion.effectiveUntil >= :quoteTime
				""", CurrencyConversionEntity.class)
			.setParameter("sourceId", binding.sourceId)
			.setParameter("nativeCurrency", nativeCurrency)
			.setParameter("reportingCurrency", reportingCurrency)
			.setParameter("quoteTime", quoteTime)
			.getResultList();
		if (matches.isEmpty()) {
			return CurrencyConversionQuote.withoutConversion(CurrencyConversionOutcome.MISSING, nativeCurrency,
					reportingCurrency, binding.sourceId, binding.sourceRevision);
		}
		CurrencyConversionEntity match = matches.getFirst();
		CurrencyConversionOutcome outcome = match.observedAt
			.isBefore(quoteTime.minus(Duration.parse(binding.maximumObservationAge))) ? CurrencyConversionOutcome.STALE
					: CurrencyConversionOutcome.QUALIFYING;
		return new CurrencyConversionQuote(outcome, nativeCurrency, reportingCurrency, match.rate, match.provenance,
				match.observedAt, match.effectiveFrom, match.effectiveUntil, binding.sourceId, binding.sourceRevision);
	}

	private static boolean assessedForPair(PriceSourceEntity source, long revision, String bindingKey) {
		return source.assessments.stream()
			.anyMatch(assessment -> assessment.revision == revision && assessment.successful
					&& List.of(assessment.capabilityResults.split("\\n")).contains("passed:" + bindingKey));
	}

}
