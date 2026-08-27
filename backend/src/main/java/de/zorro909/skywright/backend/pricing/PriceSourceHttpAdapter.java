package de.zorro909.skywright.backend.pricing;

import de.zorro909.skywright.backend.boundary.generated.api.PriceSourceBindingsApi;
import de.zorro909.skywright.backend.boundary.generated.api.PriceSourcesApi;
import de.zorro909.skywright.backend.boundary.generated.model.BindPriceSource;
import de.zorro909.skywright.backend.boundary.generated.model.CreatePriceSource;
import de.zorro909.skywright.backend.boundary.generated.model.CreateGpuPriceScheduleEntry;
import de.zorro909.skywright.backend.boundary.generated.model.GpuPriceScheduleEntry;
import de.zorro909.skywright.backend.boundary.generated.model.PriceSource;
import de.zorro909.skywright.backend.boundary.generated.model.PriceSourceAssessment;
import de.zorro909.skywright.backend.boundary.generated.model.PriceSourceKind;
import de.zorro909.skywright.backend.boundary.generated.model.PriceSourceRevision;
import de.zorro909.skywright.backend.boundary.generated.model.PriceSourceRevisionState;
import de.zorro909.skywright.backend.boundary.generated.model.PromotePriceSource;
import de.zorro909.skywright.backend.boundary.generated.model.StagePriceSourceRevision;
import de.zorro909.skywright.backend.boundary.generated.model.UpdateGpuPriceScheduleEntry;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PriceSourceHttpAdapter implements PriceSourcesApi, PriceSourceBindingsApi {

	private final PriceSourceRegistry registry;

	private final GpuPriceSchedule gpuPriceSchedule;

	PriceSourceHttpAdapter(PriceSourceRegistry registry, GpuPriceSchedule gpuPriceSchedule) {
		this.registry = registry;
		this.gpuPriceSchedule = gpuPriceSchedule;
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

	private static GpuPriceScheduleEntry entry(GpuPriceScheduleEntryView value) {
		return new GpuPriceScheduleEntry(value.sourceRevision(), value.offeringId(), value.nativeCurrency(),
				GpuPriceScheduleEntry.NativeUnitEnum.fromValue(value.nativeUnit()), value.value(),
				value.minimumQuantity(), value.billingQuantum(), value.provenance(),
				value.observedAt().atOffset(ZoneOffset.UTC), value.effectiveFrom().atOffset(ZoneOffset.UTC),
				value.effectiveUntil().atOffset(ZoneOffset.UTC), value.id(), value.revision());
	}

	private static GpuPriceScheduleEntryInput input(CreateGpuPriceScheduleEntry request) {
		requireUtc(request.getObservedAt().getOffset(), request.getEffectiveFrom().getOffset(),
				request.getEffectiveUntil().getOffset());
		return new GpuPriceScheduleEntryInput(request.getSourceRevision(), request.getOfferingId(),
				request.getNativeCurrency(), request.getNativeUnit().getValue(), request.getValue(),
				request.getMinimumQuantity(), request.getBillingQuantum(), request.getProvenance(),
				request.getObservedAt().toInstant(), request.getEffectiveFrom().toInstant(),
				request.getEffectiveUntil().toInstant());
	}

	private static GpuPriceScheduleEntryInput input(UpdateGpuPriceScheduleEntry request) {
		requireUtc(request.getObservedAt().getOffset(), request.getEffectiveFrom().getOffset(),
				request.getEffectiveUntil().getOffset());
		return new GpuPriceScheduleEntryInput(request.getSourceRevision(), request.getOfferingId(),
				request.getNativeCurrency(), request.getNativeUnit().getValue(), request.getValue(),
				request.getMinimumQuantity(), request.getBillingQuantum(), request.getProvenance(),
				request.getObservedAt().toInstant(), request.getEffectiveFrom().toInstant(),
				request.getEffectiveUntil().toInstant());
	}

	private static void requireUtc(ZoneOffset... offsets) {
		if (java.util.Arrays.stream(offsets).anyMatch(offset -> !ZoneOffset.UTC.equals(offset))) {
			throw new PriceSourceValidationException("GPU_PRICE_SCHEDULE_INSTANT_INVALID",
					"Schedule instants must use the UTC offset");
		}
	}

}
