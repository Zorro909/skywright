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
		String bindingKey = PriceSourceBindingKey.currencyPair(nativeCurrency, reportingCurrency).value();
		PriceSourceBindingEntity binding = this.entities.find(PriceSourceBindingEntity.class, bindingKey);
		if (binding == null) {
			return CurrencyConversionQuote.withoutConversion(CurrencyConversionOutcome.UNAVAILABLE, nativeCurrency,
					reportingCurrency, null, null, null, null, null, null, null);
		}
		Duration maximumAge = Duration.parse(binding.maximumObservationAge);
		PriceSourceEntity source = this.entities.find(PriceSourceEntity.class, binding.sourceId);
		PriceSourceAssessmentValue assessment = source == null ? null
				: assessmentForPair(source, binding.sourceRevision, bindingKey);
		if (source == null || source.activeRevision == null || source.activeRevision != binding.sourceRevision
				|| assessment == null) {
			return CurrencyConversionQuote.withoutConversion(CurrencyConversionOutcome.UNAVAILABLE, nativeCurrency,
					reportingCurrency, binding.sourceId, binding.sourceRevision,
					source == null ? null : source.conversionScheduleRevision,
					source == null ? null : source.kind.wireValue(), maximumAge,
					assessment == null ? null : assessment.observedFrom,
					assessment == null ? null : assessment.observedUntil);
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
					reportingCurrency, binding.sourceId, binding.sourceRevision, source.conversionScheduleRevision,
					source.kind.wireValue(), maximumAge, assessment.observedFrom, assessment.observedUntil);
		}
		CurrencyConversionEntity match = matches.getFirst();
		if (match.observedAt.isAfter(quoteTime)) {
			return CurrencyConversionQuote.withoutConversion(CurrencyConversionOutcome.MISSING, nativeCurrency,
					reportingCurrency, binding.sourceId, binding.sourceRevision, source.conversionScheduleRevision,
					source.kind.wireValue(), maximumAge, assessment.observedFrom, assessment.observedUntil);
		}
		CurrencyConversionOutcome outcome = match.observedAt.isBefore(quoteTime.minus(maximumAge))
				? CurrencyConversionOutcome.STALE : CurrencyConversionOutcome.QUALIFYING;
		return new CurrencyConversionQuote(outcome, nativeCurrency, reportingCurrency, match.rate, match.provenance,
				match.observedAt, match.effectiveFrom, match.effectiveUntil, binding.sourceId, binding.sourceRevision,
				source.conversionScheduleRevision, source.kind.wireValue(), maximumAge, assessment.observedFrom,
				assessment.observedUntil);
	}

	private static PriceSourceAssessmentValue assessmentForPair(PriceSourceEntity source, long revision,
			String bindingKey) {
		List<PriceSourceAssessmentValue> assessments = source.assessments.stream()
			.filter(assessment -> assessment.revision == revision && assessment.successful
					&& assessment.capabilityResults.contains("passed:" + bindingKey))
			.toList();
		return assessments.isEmpty() ? null : assessments.getLast();
	}

}
