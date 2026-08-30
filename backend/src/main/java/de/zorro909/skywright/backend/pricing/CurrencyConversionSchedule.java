package de.zorro909.skywright.backend.pricing;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class CurrencyConversionSchedule {

	private final EntityManager entities;

	CurrencyConversionSchedule(EntityManager entities) {
		this.entities = entities;
	}

	CurrencyConversionScheduleView create(UUID sourceId, long expectedScheduleRevision, CurrencyConversionValue value) {
		PriceSourceEntity source = sourceForMutation(sourceId);
		requireOperatorSchedule(source);
		requireRevision(source, expectedScheduleRevision);
		CurrencyConversionValue valid = validate(value);
		this.entities.persist(new CurrencyConversionEntity(UUID.randomUUID(), sourceId, valid));
		source.conversionScheduleRevision++;
		invalidateCandidateAssessment(source);
		flush();
		return view(source);
	}

	CurrencyConversionScheduleView replace(UUID sourceId, UUID conversionId, long expectedScheduleRevision,
			CurrencyConversionValue value) {
		PriceSourceEntity source = sourceForMutation(sourceId);
		requireOperatorSchedule(source);
		requireRevision(source, expectedScheduleRevision);
		CurrencyConversionEntity conversion = conversion(sourceId, conversionId);
		conversion.replace(validate(value));
		source.conversionScheduleRevision++;
		invalidateCandidateAssessment(source);
		flush();
		return view(source);
	}

	CurrencyConversionScheduleView delete(UUID sourceId, UUID conversionId, long expectedScheduleRevision) {
		PriceSourceEntity source = sourceForMutation(sourceId);
		requireOperatorSchedule(source);
		requireRevision(source, expectedScheduleRevision);
		this.entities.remove(conversion(sourceId, conversionId));
		source.conversionScheduleRevision++;
		invalidateCandidateAssessment(source);
		flush();
		return view(source);
	}

	@Transactional(readOnly = true)
	CurrencyConversionScheduleView get(UUID sourceId) {
		PriceSourceEntity source = source(sourceId);
		requireOperatorSchedule(source);
		return view(source);
	}

	private PriceSourceEntity source(UUID sourceId) {
		PriceSourceEntity source = this.entities.find(PriceSourceEntity.class, sourceId);
		if (source == null) {
			throw new PriceSourceNotFoundException();
		}
		return source;
	}

	private PriceSourceEntity sourceForMutation(UUID sourceId) {
		PriceSourceEntity source = this.entities.find(PriceSourceEntity.class, sourceId,
				LockModeType.PESSIMISTIC_WRITE);
		if (source == null) {
			throw new PriceSourceNotFoundException();
		}
		return source;
	}

	private CurrencyConversionEntity conversion(UUID sourceId, UUID conversionId) {
		CurrencyConversionEntity result = this.entities.find(CurrencyConversionEntity.class, conversionId);
		if (result == null || !result.sourceId.equals(sourceId)) {
			throw new CurrencyConversionNotFoundException();
		}
		return result;
	}

	private CurrencyConversionScheduleView view(PriceSourceEntity source) {
		List<CurrencyConversionView> entries = this.entities.createQuery("""
				select conversion from CurrencyConversionEntity conversion
				where conversion.sourceId = :sourceId
				order by conversion.nativeCurrency, conversion.reportingCurrency, conversion.effectiveFrom
				""", CurrencyConversionEntity.class)
			.setParameter("sourceId", source.id)
			.getResultStream()
			.map(CurrencyConversionEntity::view)
			.toList();
		return new CurrencyConversionScheduleView(source.id, source.conversionScheduleRevision, entries);
	}

	private static CurrencyConversionValue validate(CurrencyConversionValue value) {
		String nativeCurrency = currency(value.nativeCurrency());
		String reportingCurrency = currency(value.reportingCurrency());
		if (nativeCurrency.equals(reportingCurrency)) {
			throw invalid("Native and reporting currencies must differ");
		}
		BigDecimal rate = value.rate();
		if (rate == null || rate.signum() <= 0) {
			throw invalid("The conversion rate must be positive");
		}
		Map<String, Object> provenance;
		try {
			provenance = PriceRateProvenance.validate(value.provenance());
		}
		catch (IllegalArgumentException failure) {
			throw invalid("Provenance does not match the non-secret price evidence shape");
		}
		Instant observedAt = requireInstant(value.observedAt());
		Instant effectiveFrom = requireInstant(value.effectiveFrom());
		Instant effectiveUntil = requireInstant(value.effectiveUntil());
		if (!effectiveFrom.isBefore(effectiveUntil)) {
			throw invalid("The effective interval must end after it starts");
		}
		return new CurrencyConversionValue(nativeCurrency, reportingCurrency, rate, provenance, observedAt,
				effectiveFrom, effectiveUntil);
	}

	private static String currency(String value) {
		try {
			if (value == null || !value.matches("[A-Z]{3}")) {
				throw new IllegalArgumentException();
			}
			return Currency.getInstance(value).getCurrencyCode();
		}
		catch (IllegalArgumentException failure) {
			throw invalid("Currencies must be ISO 4217 alphabetic codes");
		}
	}

	private static Instant requireInstant(Instant value) {
		if (value == null) {
			throw invalid("Observation and effective interval times are required");
		}
		return value;
	}

	private static void requireOperatorSchedule(PriceSourceEntity source) {
		if (source.kind != PriceSourceKind.OPERATOR_SCHEDULE) {
			throw invalid("Currency conversion entries require an operator-maintained Price Source");
		}
	}

	private static void requireRevision(PriceSourceEntity source, long expected) {
		if (source.conversionScheduleRevision != expected) {
			throw new PriceSourceConflictException("CURRENCY_CONVERSION_SCHEDULE_REVISION_CONFLICT",
					"The currency conversion schedule changed; reload it and retry");
		}
	}

	private static void invalidateCandidateAssessment(PriceSourceEntity source) {
		if (source.candidateRevision != null) {
			source.scheduleRevision++;
			source.assessedScheduleRevision = null;
		}
	}

	private void flush() {
		try {
			this.entities.flush();
		}
		catch (RuntimeException failure) {
			if (hasSqlState(failure, "23P01")) {
				throw new PriceSourceConflictException("CURRENCY_CONVERSION_INTERVAL_OVERLAP",
						"Effective intervals for one currency pair must not overlap or share a boundary");
			}
			throw failure;
		}
	}

	private static boolean hasSqlState(Throwable failure, String sqlState) {
		for (Throwable current = failure; current != null; current = current.getCause()) {
			if (current instanceof ConstraintViolationException constraint
					&& sqlState.equals(constraint.getSQLState())) {
				return true;
			}
		}
		return false;
	}

	private static PriceSourceValidationException invalid(String detail) {
		return new PriceSourceValidationException("CURRENCY_CONVERSION_INVALID", detail);
	}

}
