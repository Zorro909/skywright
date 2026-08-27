package de.zorro909.skywright.backend.pricing;

import de.zorro909.skywright.backend.boundary.generated.api.PriceSourceBindingsApi;
import de.zorro909.skywright.backend.boundary.generated.api.PriceSourcesApi;
import de.zorro909.skywright.backend.boundary.generated.model.BindPriceSource;
import de.zorro909.skywright.backend.boundary.generated.model.CreateCurrencyConversion;
import de.zorro909.skywright.backend.boundary.generated.model.CreatePriceSource;
import de.zorro909.skywright.backend.boundary.generated.model.CurrencyConversion;
import de.zorro909.skywright.backend.boundary.generated.model.PriceSource;
import de.zorro909.skywright.backend.boundary.generated.model.PriceSourceAssessment;
import de.zorro909.skywright.backend.boundary.generated.model.PriceSourceKind;
import de.zorro909.skywright.backend.boundary.generated.model.PriceSourceRevision;
import de.zorro909.skywright.backend.boundary.generated.model.PriceSourceRevisionState;
import de.zorro909.skywright.backend.boundary.generated.model.PromotePriceSource;
import de.zorro909.skywright.backend.boundary.generated.model.ReplaceCurrencyConversion;
import de.zorro909.skywright.backend.boundary.generated.model.StagePriceSourceRevision;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PriceSourceHttpAdapter implements PriceSourcesApi, PriceSourceBindingsApi {

	private final PriceSourceRegistry registry;

	private final CurrencyConversionSchedule conversionSchedule;

	PriceSourceHttpAdapter(PriceSourceRegistry registry, CurrencyConversionSchedule conversionSchedule) {
		this.registry = registry;
		this.conversionSchedule = conversionSchedule;
	}

	@Override
	public ResponseEntity<de.zorro909.skywright.backend.boundary.generated.model.CurrencyConversionSchedule> listCurrencyConversions(
			UUID sourceId) {
		return ResponseEntity.ok(schedule(this.conversionSchedule.get(sourceId)));
	}

	@Override
	public ResponseEntity<de.zorro909.skywright.backend.boundary.generated.model.CurrencyConversionSchedule> createCurrencyConversion(
			UUID sourceId, CreateCurrencyConversion request) {
		return ResponseEntity.status(201)
			.body(schedule(
					this.conversionSchedule.create(sourceId, request.getExpectedScheduleRevision(), value(request))));
	}

	@Override
	public ResponseEntity<de.zorro909.skywright.backend.boundary.generated.model.CurrencyConversionSchedule> replaceCurrencyConversion(
			UUID sourceId, UUID conversionId, ReplaceCurrencyConversion request) {
		return ResponseEntity.ok(schedule(this.conversionSchedule.replace(sourceId, conversionId,
				request.getExpectedScheduleRevision(), value(request))));
	}

	@Override
	public ResponseEntity<de.zorro909.skywright.backend.boundary.generated.model.CurrencyConversionSchedule> deleteCurrencyConversion(
			Long expectedScheduleRevision, UUID sourceId, UUID conversionId) {
		return ResponseEntity
			.ok(schedule(this.conversionSchedule.delete(sourceId, conversionId, expectedScheduleRevision.longValue())));
	}

	@Override
	public ResponseEntity<PriceSource> createPriceSource(CreatePriceSource request) {
		UUID id = this.registry.register(request.getName(), request.getKind().getValue(),
				request.getCredentialBindingId(), request.getConfiguration());
		return ResponseEntity.status(201).body(source(this.registry.get(id)));
	}

	@Override
	public ResponseEntity<PriceSource> getPriceSource(UUID sourceId) {
		return ResponseEntity.ok(source(this.registry.get(sourceId)));
	}

	@Override
	public ResponseEntity<List<PriceSource>> listPriceSources() {
		return ResponseEntity.ok(this.registry.list().stream().map(PriceSourceHttpAdapter::source).toList());
	}

	@Override
	public ResponseEntity<PriceSource> stagePriceSourceRevision(UUID sourceId, StagePriceSourceRevision request) {
		this.registry.stage(sourceId, request.getExpectedRegistrationRevision(), request.getConfiguration());
		return ResponseEntity.ok(source(this.registry.get(sourceId)));
	}

	@Override
	public ResponseEntity<PriceSource> assessPriceSource(UUID sourceId) {
		this.registry.assess(sourceId);
		return ResponseEntity.ok(source(this.registry.get(sourceId)));
	}

	@Override
	public ResponseEntity<PriceSource> promotePriceSource(UUID sourceId, PromotePriceSource request) {
		this.registry.promote(sourceId, request.getExpectedRegistrationRevision(), request.getRevision());
		return ResponseEntity.ok(source(this.registry.get(sourceId)));
	}

	@Override
	public ResponseEntity<de.zorro909.skywright.backend.boundary.generated.model.PriceSourceBinding> bindPriceSource(
			String bindingKey, BindPriceSource request) {
		return ResponseEntity
			.ok(binding(this.registry.bind(bindingKey, request.getSourceId(), request.getSourceRevision(),
					parseDuration(request.getMaximumObservationAge()), request.getExpectedBindingRevision())));
	}

	@Override
	public ResponseEntity<List<de.zorro909.skywright.backend.boundary.generated.model.PriceSourceBinding>> listPriceSourceBindings() {
		return ResponseEntity.ok(this.registry.bindings().stream().map(PriceSourceHttpAdapter::binding).toList());
	}

	private static PriceSource source(PriceSourceView value) {
		return new PriceSource(value.id(), value.name(), PriceSourceKind.fromValue(value.kind()),
				value.registrationRevision(), value.activeRevision(), value.candidateRevision(),
				value.credentialBindingId(),
				value.revisions()
					.stream()
					.map(revision -> new PriceSourceRevision(revision.revision(), state(value, revision.revision()),
							revision.configuration()))
					.toList(),
				value.assessments()
					.stream()
					.map(assessment -> new PriceSourceAssessment(assessment.id(), assessment.revision(),
							assessment.successful(), assessment.capabilityResults(),
							assessment.observedFrom().atOffset(ZoneOffset.UTC),
							assessment.observedUntil().atOffset(ZoneOffset.UTC)))
					.toList());
	}

	private static PriceSourceRevisionState state(PriceSourceView source, long revision) {
		if (source.activeRevision() != null && source.activeRevision() == revision) {
			return PriceSourceRevisionState.ACTIVE;
		}
		if (source.candidateRevision() != null && source.candidateRevision() == revision) {
			return PriceSourceRevisionState.CANDIDATE;
		}
		return PriceSourceRevisionState.HISTORICAL;
	}

	private static de.zorro909.skywright.backend.boundary.generated.model.PriceSourceBinding binding(
			PriceSourceBindingView value) {
		return new de.zorro909.skywright.backend.boundary.generated.model.PriceSourceBinding(value.bindingKey(),
				value.bindingRevision(), value.sourceId(), value.sourceRevision(),
				value.maximumObservationAge().toString());
	}

	private static Duration parseDuration(String value) {
		try {
			return Duration.parse(value);
		}
		catch (RuntimeException error) {
			throw new PriceSourceValidationException("PRICE_SOURCE_FRESHNESS_INVALID",
					"Maximum observation age must be an ISO 8601 duration");
		}
	}

	private static CurrencyConversionValue value(CreateCurrencyConversion request) {
		return value(request.getNativeCurrency(), request.getReportingCurrency(), request.getRate(),
				request.getProvenance(), request.getObservedAt(), request.getEffectiveFrom(),
				request.getEffectiveUntil());
	}

	private static CurrencyConversionValue value(ReplaceCurrencyConversion request) {
		return value(request.getNativeCurrency(), request.getReportingCurrency(), request.getRate(),
				request.getProvenance(), request.getObservedAt(), request.getEffectiveFrom(),
				request.getEffectiveUntil());
	}

	private static CurrencyConversionValue value(String nativeCurrency, String reportingCurrency, String rate,
			String provenance, java.time.OffsetDateTime observedAt, java.time.OffsetDateTime effectiveFrom,
			java.time.OffsetDateTime effectiveUntil) {
		try {
			if (!utc(observedAt) || !utc(effectiveFrom) || !utc(effectiveUntil)) {
				throw new PriceSourceValidationException("CURRENCY_CONVERSION_INVALID",
						"Observation and effective interval times must use UTC");
			}
			return new CurrencyConversionValue(nativeCurrency, reportingCurrency, new BigDecimal(rate), provenance,
					observedAt == null ? null : observedAt.toInstant(),
					effectiveFrom == null ? null : effectiveFrom.toInstant(),
					effectiveUntil == null ? null : effectiveUntil.toInstant());
		}
		catch (NumberFormatException failure) {
			throw new PriceSourceValidationException("CURRENCY_CONVERSION_INVALID",
					"The conversion rate must be an exact positive decimal");
		}
	}

	private static boolean utc(java.time.OffsetDateTime value) {
		return value == null || value.getOffset().equals(ZoneOffset.UTC);
	}

	private static de.zorro909.skywright.backend.boundary.generated.model.CurrencyConversionSchedule schedule(
			CurrencyConversionScheduleView value) {
		return new de.zorro909.skywright.backend.boundary.generated.model.CurrencyConversionSchedule(value.sourceId(),
				value.scheduleRevision(), value.entries().stream().map(PriceSourceHttpAdapter::conversion).toList());
	}

	private static CurrencyConversion conversion(CurrencyConversionView value) {
		return new CurrencyConversion(value.id(), value.nativeCurrency(), value.reportingCurrency(),
				value.rate().toPlainString(), value.provenance(), value.observedAt().atOffset(ZoneOffset.UTC),
				value.effectiveFrom().atOffset(ZoneOffset.UTC), value.effectiveUntil().atOffset(ZoneOffset.UTC));
	}

}
