package de.zorro909.skywright.backend.pricing;

import de.zorro909.skywright.backend.boundary.generated.api.PriceSourceBindingsApi;
import de.zorro909.skywright.backend.boundary.generated.api.PriceSourcesApi;
import de.zorro909.skywright.backend.boundary.generated.model.BindPriceSource;
import de.zorro909.skywright.backend.boundary.generated.model.CreateCurrencyConversion;
import de.zorro909.skywright.backend.boundary.generated.model.CreateGpuPriceScheduleEntry;
import de.zorro909.skywright.backend.boundary.generated.model.CreatePriceSource;
import de.zorro909.skywright.backend.boundary.generated.model.CurrencyConversion;
import de.zorro909.skywright.backend.boundary.generated.model.GpuPriceScheduleEntry;
import de.zorro909.skywright.backend.boundary.generated.model.PriceSource;
import de.zorro909.skywright.backend.boundary.generated.model.PriceSourceAssessment;
import de.zorro909.skywright.backend.boundary.generated.model.PriceSourceRevision;
import de.zorro909.skywright.backend.boundary.generated.model.PriceSourceRevisionState;
import de.zorro909.skywright.backend.boundary.generated.model.PromotePriceSource;
import de.zorro909.skywright.backend.boundary.generated.model.ReplaceCurrencyConversion;
import de.zorro909.skywright.backend.boundary.generated.model.StagePriceSourceRevision;
import de.zorro909.skywright.backend.boundary.generated.model.UpdateGpuPriceScheduleEntry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PriceSourceHttpAdapter implements PriceSourcesApi, PriceSourceBindingsApi {

	private final PriceSourceRegistry registry;

	private final CurrencyConversionSchedule conversionSchedule;

	private final GpuPriceSchedule gpuPriceSchedule;

	PriceSourceHttpAdapter(PriceSourceRegistry registry, CurrencyConversionSchedule conversionSchedule,
			GpuPriceSchedule gpuPriceSchedule) {
		this.registry = registry;
		this.conversionSchedule = conversionSchedule;
		this.gpuPriceSchedule = gpuPriceSchedule;
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
	public ResponseEntity<GpuPriceScheduleEntry> createGpuPriceScheduleEntry(UUID sourceId,
			CreateGpuPriceScheduleEntry request) {
		return ResponseEntity.status(201).body(entry(this.gpuPriceSchedule.create(sourceId, input(request))));
	}

	@Override
	public ResponseEntity<GpuPriceScheduleEntry> getGpuPriceScheduleEntry(UUID sourceId, UUID entryId) {
		return ResponseEntity.ok(entry(this.gpuPriceSchedule.get(sourceId, entryId)));
	}

	@Override
	public ResponseEntity<List<GpuPriceScheduleEntry>> listGpuPriceScheduleEntries(UUID sourceId) {
		return ResponseEntity
			.ok(this.gpuPriceSchedule.list(sourceId).stream().map(PriceSourceHttpAdapter::entry).toList());
	}

	@Override
	public ResponseEntity<GpuPriceScheduleEntry> updateGpuPriceScheduleEntry(UUID sourceId, UUID entryId,
			UpdateGpuPriceScheduleEntry request) {
		return ResponseEntity
			.ok(entry(this.gpuPriceSchedule.update(sourceId, entryId, request.getExpectedRevision(), input(request))));
	}

	@Override
	public ResponseEntity<Void> deleteGpuPriceScheduleEntry(Long expectedRevision, UUID sourceId, UUID entryId) {
		this.gpuPriceSchedule.delete(sourceId, entryId, expectedRevision);
		return ResponseEntity.noContent().build();
	}

	@Override
	public ResponseEntity<PriceSource> createPriceSource(CreatePriceSource request) {
		UUID id = this.registry.register(request.getName(), request.getKind().getValue(),
				request.getCredentialBindingId(), configuration(request.getConfiguration()));
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
		this.registry.stage(sourceId, request.getExpectedRegistrationRevision(),
				configuration(request.getConfiguration()));
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
		return new PriceSource(value.id(), value.name(),
				de.zorro909.skywright.backend.boundary.generated.model.PriceSourceKind
					.fromValue(value.kind().wireValue()),
				value.registrationRevision(), value.activeRevision(), value.candidateRevision(),
				value.credentialBindingId(),
				value.revisions()
					.stream()
					.map(revision -> new PriceSourceRevision(revision.revision(), state(value, revision.revision()),
							configuration(revision.configuration())))
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
			de.zorro909.skywright.backend.boundary.generated.model.PriceRateProvenance provenance,
			java.time.OffsetDateTime observedAt, java.time.OffsetDateTime effectiveFrom,
			java.time.OffsetDateTime effectiveUntil) {
		try {
			if (!utc(observedAt) || !utc(effectiveFrom) || !utc(effectiveUntil)) {
				throw new PriceSourceValidationException("CURRENCY_CONVERSION_INVALID",
						"Observation and effective interval times must use UTC");
			}
			return new CurrencyConversionValue(nativeCurrency, reportingCurrency, new BigDecimal(rate),
					provenance(provenance), observedAt == null ? null : observedAt.toInstant(),
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
				value.rate().toPlainString(), provenance(value.provenance()),
				value.observedAt().atOffset(ZoneOffset.UTC), value.effectiveFrom().atOffset(ZoneOffset.UTC),
				value.effectiveUntil().atOffset(ZoneOffset.UTC));
	}

	private static GpuPriceScheduleEntry entry(GpuPriceScheduleEntryView value) {
		return new GpuPriceScheduleEntry(value.id(), value.revision(), value.sourceRevision(), value.offeringId(),
				value.nativeCurrency(), GpuPriceScheduleEntry.NativeUnitEnum.fromValue(value.nativeUnit()),
				value.value(), value.minimumQuantity(), value.billingQuantum(), provenance(value.provenance()),
				value.observedAt().atOffset(ZoneOffset.UTC), value.effectiveFrom().atOffset(ZoneOffset.UTC),
				value.effectiveUntil().atOffset(ZoneOffset.UTC));
	}

	private static GpuPriceScheduleEntryInput input(CreateGpuPriceScheduleEntry request) {
		requireUtc(request.getObservedAt().getOffset(), request.getEffectiveFrom().getOffset(),
				request.getEffectiveUntil().getOffset());
		return new GpuPriceScheduleEntryInput(request.getSourceRevision(), request.getOfferingId(),
				request.getNativeCurrency(), request.getNativeUnit().getValue(), request.getValue(),
				request.getMinimumQuantity(), request.getBillingQuantum(), provenance(request.getProvenance()),
				request.getObservedAt().toInstant(), request.getEffectiveFrom().toInstant(),
				request.getEffectiveUntil().toInstant());
	}

	private static GpuPriceScheduleEntryInput input(UpdateGpuPriceScheduleEntry request) {
		requireUtc(request.getObservedAt().getOffset(), request.getEffectiveFrom().getOffset(),
				request.getEffectiveUntil().getOffset());
		return new GpuPriceScheduleEntryInput(request.getSourceRevision(), request.getOfferingId(),
				request.getNativeCurrency(), request.getNativeUnit().getValue(), request.getValue(),
				request.getMinimumQuantity(), request.getBillingQuantum(), provenance(request.getProvenance()),
				request.getObservedAt().toInstant(), request.getEffectiveFrom().toInstant(),
				request.getEffectiveUntil().toInstant());
	}

	private static void requireUtc(ZoneOffset... offsets) {
		if (java.util.Arrays.stream(offsets).anyMatch(offset -> !ZoneOffset.UTC.equals(offset))) {
			throw new PriceSourceValidationException("GPU_PRICE_SCHEDULE_INSTANT_INVALID",
					"Schedule instants must use the UTC offset");
		}
	}

	private static Map<String, Object> configuration(
			de.zorro909.skywright.backend.boundary.generated.model.PriceSourceConfiguration value) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("capabilities", value.getCapabilities());
		if (value.getNativeCurrencies() != null && !value.getNativeCurrencies().isEmpty()) {
			result.put("nativeCurrencies", value.getNativeCurrencies());
		}
		if (value.getNativeUnits() != null && !value.getNativeUnits().isEmpty()) {
			result.put("nativeUnits", value.getNativeUnits().stream().map(item -> item.getValue()).toList());
		}
		if (value.getTargets() != null && !value.getTargets().isEmpty()) {
			result.put("targets", value.getTargets());
		}
		if (value.getRates() != null && !value.getRates().isEmpty()) {
			result.put("rates",
					value.getRates()
						.stream()
						.map(rate -> Map.of("amount", rate.getAmount(), "currency", rate.getCurrency()))
						.toList());
		}
		if (value.getEndpoint() != null) {
			result.put("endpoint", value.getEndpoint());
		}
		return result;
	}

	@SuppressWarnings("unchecked")
	private static de.zorro909.skywright.backend.boundary.generated.model.PriceSourceConfiguration configuration(
			Map<String, Object> value) {
		var result = new de.zorro909.skywright.backend.boundary.generated.model.PriceSourceConfiguration();
		result.setCapabilities((List<String>) value.get("capabilities"));
		result.setNativeCurrencies((List<String>) value.get("nativeCurrencies"));
		result.setNativeUnits(value.containsKey("nativeUnits") ? ((List<String>) value.get("nativeUnits")).stream()
			.map(de.zorro909.skywright.backend.boundary.generated.model.PriceSourceConfiguration.NativeUnitsEnum::fromValue)
			.toList() : null);
		result.setTargets((List<String>) value.get("targets"));
		result.setRates(value.containsKey("rates") ? ((List<Map<String, Object>>) value.get("rates")).stream()
			.map(rate -> new de.zorro909.skywright.backend.boundary.generated.model.DeclaredPriceRate(
					rate.get("amount").toString(), rate.get("currency").toString()))
			.toList() : null);
		result.setEndpoint((String) value.get("endpoint"));
		return result;
	}

	private static Map<String, Object> provenance(
			de.zorro909.skywright.backend.boundary.generated.model.PriceRateProvenance value) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("source", value.getSource());
		put(result, "documentRevision", value.getDocumentRevision());
		put(result, "valueKind", value.getValueKind() == null ? null : value.getValueKind().getValue());
		put(result, "skyPilotVersion", value.getSkyPilotVersion());
		put(result, "catalogRequestId", value.getCatalogRequestId());
		put(result, "target", value.getTarget());
		put(result, "region", value.getRegion());
		put(result, "instanceType", value.getInstanceType());
		put(result, "gpuModel", value.getGpuModel());
		put(result, "gpuCount", value.getGpuCount());
		put(result, "purchaseMode", value.getPurchaseMode() == null ? null : value.getPurchaseMode().getValue());
		return result;
	}

	private static de.zorro909.skywright.backend.boundary.generated.model.PriceRateProvenance provenance(
			Map<String, Object> value) {
		var result = new de.zorro909.skywright.backend.boundary.generated.model.PriceRateProvenance(
				(String) value.get("source"));
		result.setDocumentRevision((String) value.get("documentRevision"));
		if (value.containsKey("valueKind")) {
			result.setValueKind(de.zorro909.skywright.backend.boundary.generated.model.PriceRateProvenance.ValueKindEnum
				.fromValue((String) value.get("valueKind")));
		}
		result.setSkyPilotVersion((String) value.get("skyPilotVersion"));
		result.setCatalogRequestId((String) value.get("catalogRequestId"));
		result.setTarget((String) value.get("target"));
		result.setRegion((String) value.get("region"));
		result.setInstanceType((String) value.get("instanceType"));
		result.setGpuModel((String) value.get("gpuModel"));
		if (value.containsKey("gpuCount")) {
			result.setGpuCount(((Number) value.get("gpuCount")).intValue());
		}
		if (value.containsKey("purchaseMode")) {
			result.setPurchaseMode(
					de.zorro909.skywright.backend.boundary.generated.model.PriceRateProvenance.PurchaseModeEnum
						.fromValue((String) value.get("purchaseMode")));
		}
		return result;
	}

	private static void put(Map<String, Object> target, String key, Object value) {
		if (value != null) {
			target.put(key, value);
		}
	}

}
