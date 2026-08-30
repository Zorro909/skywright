package de.zorro909.skywright.backend.pricing;

import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class GpuPriceSchedule implements GpuPriceScheduleReader {

	private static final String NATIVE_UNIT = "instance-hour";

	private final EntityManager entities;

	GpuPriceSchedule(EntityManager entities) {
		this.entities = entities;
	}

	public GpuPriceScheduleEntryView create(UUID sourceId, GpuPriceScheduleEntryInput input) {
		PriceSourceEntity source = validateSourceAndInput(sourceId, input);
		GpuPriceScheduleEntryEntity entry = GpuPriceScheduleEntryEntity.create(UUID.randomUUID(), sourceId, input);
		this.entities.persist(entry);
		invalidateCandidateAssessment(source, input.sourceRevision());
		flushConstraints();
		return view(entry);
	}

	@Transactional(readOnly = true)
	public GpuPriceScheduleEntryView get(UUID sourceId, UUID entryId) {
		return view(entry(sourceId, entryId));
	}

	@Transactional(readOnly = true)
	public List<GpuPriceScheduleEntryView> list(UUID sourceId) {
		requireOperatorSource(sourceId);
		return this.entities
			.createQuery(
					"select entry from GpuPriceScheduleEntryEntity entry where entry.sourceId = :sourceId "
							+ "order by entry.sourceRevision, entry.offeringId, entry.effectiveFrom, entry.id",
					GpuPriceScheduleEntryEntity.class)
			.setParameter("sourceId", sourceId)
			.getResultStream()
			.map(this::view)
			.toList();
	}

	public GpuPriceScheduleEntryView update(UUID sourceId, UUID entryId, long expectedRevision,
			GpuPriceScheduleEntryInput input) {
		PriceSourceEntity source = validateSourceAndInput(sourceId, input);
		GpuPriceScheduleEntryEntity entry = entry(sourceId, entryId);
		requireRevision(entry, expectedRevision);
		long previousSourceRevision = entry.sourceRevision;
		entry.replace(input);
		entry.revision++;
		invalidateCandidateAssessment(source, previousSourceRevision, input.sourceRevision());
		flushConstraints();
		return entry.view();
	}

	public void delete(UUID sourceId, UUID entryId, long expectedRevision) {
		GpuPriceScheduleEntryEntity entry = entry(sourceId, entryId);
		requireRevision(entry, expectedRevision);
		PriceSourceEntity source = requireOperatorSource(sourceId);
		invalidateCandidateAssessment(source, entry.sourceRevision);
		this.entities.remove(entry);
		flushConstraints();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<GpuPriceScheduleEntryView> rate(GpuComputePriceQuery query) {
		PriceSourceEntity source = requireOperatorSource(query.sourceId());
		if (source.revisions.stream().noneMatch(revision -> revision.revision == query.sourceRevision())) {
			throw invalid("GPU_PRICE_SCHEDULE_SOURCE_REVISION_INVALID",
					"The price query must select an existing Price Source revision");
		}
		return this.entities
			.createQuery("select entry from GpuPriceScheduleEntryEntity entry "
					+ "where entry.sourceId = :sourceId and entry.sourceRevision = :sourceRevision "
					+ "and entry.offeringId = :offeringId and entry.effectiveFrom <= :quoteTime "
					+ "and entry.effectiveUntil > :quoteTime", GpuPriceScheduleEntryEntity.class)
			.setParameter("sourceId", query.sourceId())
			.setParameter("sourceRevision", query.sourceRevision())
			.setParameter("offeringId", query.offeringId())
			.setParameter("quoteTime", query.quoteTime())
			.getResultStream()
			.findFirst()
			.map(this::view);
	}

	List<GpuPriceScheduleEntryView> entries(UUID sourceId, long sourceRevision) {
		return this.entities
			.createQuery(
					"select entry from GpuPriceScheduleEntryEntity entry "
							+ "where entry.sourceId = :sourceId and entry.sourceRevision = :sourceRevision",
					GpuPriceScheduleEntryEntity.class)
			.setParameter("sourceId", sourceId)
			.setParameter("sourceRevision", sourceRevision)
			.getResultStream()
			.map(this::view)
			.toList();
	}

	private PriceSourceEntity validateSourceAndInput(UUID sourceId, GpuPriceScheduleEntryInput input) {
		PriceSourceEntity source = requireOperatorSource(sourceId);
		if (input == null || input.sourceRevision() <= 0 || input.offeringId() == null || input.observedAt() == null
				|| input.effectiveFrom() == null || input.effectiveUntil() == null || input.provenance() == null) {
			throw invalid("GPU_PRICE_SCHEDULE_ENTRY_INVALID", "Required GPU price schedule facts are missing");
		}
		if (source.revisions.stream().noneMatch(revision -> revision.revision == input.sourceRevision())) {
			throw invalid("GPU_PRICE_SCHEDULE_SOURCE_REVISION_INVALID",
					"The schedule entry must select an existing Price Source revision");
		}
		Long offeringCount = this.entities
			.createQuery("select count(offering) from EligibleGpuOfferingEntity offering where offering.id = :id",
					Long.class)
			.setParameter("id", input.offeringId())
			.getSingleResult();
		if (offeringCount == 0) {
			throw invalid("GPU_PRICE_SCHEDULE_OFFERING_INVALID",
					"The schedule entry must select an Eligible GPU Offering");
		}
		try {
			if (!Currency.getInstance(input.nativeCurrency()).getCurrencyCode().equals(input.nativeCurrency())) {
				throw new IllegalArgumentException();
			}
		}
		catch (IllegalArgumentException | NullPointerException error) {
			throw invalid("GPU_PRICE_SCHEDULE_CURRENCY_INVALID", "Native currency must be an ISO 4217 code");
		}
		if (!NATIVE_UNIT.equals(input.nativeUnit())) {
			throw invalid("GPU_PRICE_SCHEDULE_UNIT_INVALID", "Native unit must be instance-hour");
		}
		requireNonNegative(input.value(), "GPU_PRICE_SCHEDULE_VALUE_INVALID", "Exact value must not be negative");
		requirePositive(input.minimumQuantity(), "GPU_PRICE_SCHEDULE_MINIMUM_INVALID",
				"Minimum quantity must be positive");
		requirePositive(input.billingQuantum(), "GPU_PRICE_SCHEDULE_QUANTUM_INVALID",
				"Billing quantum must be positive");
		if (!input.effectiveFrom().isBefore(input.effectiveUntil())) {
			throw invalid("GPU_PRICE_SCHEDULE_INTERVAL_INVALID", "Effective from must be before effective until");
		}
		try {
			PriceRateProvenance.validate(input.provenance());
		}
		catch (IllegalArgumentException failure) {
			throw invalid("PRICE_SOURCE_PROVENANCE_INVALID",
					"Provenance does not match the non-secret price evidence shape");
		}
		return source;
	}

	private static void invalidateCandidateAssessment(PriceSourceEntity source, long... affectedRevisions) {
		if (source.candidateRevision == null) {
			return;
		}
		for (long affectedRevision : affectedRevisions) {
			if (source.candidateRevision == affectedRevision) {
				source.scheduleRevision++;
				source.assessedScheduleRevision = null;
				return;
			}
		}
	}

	private PriceSourceEntity requireOperatorSource(UUID sourceId) {
		PriceSourceEntity source = this.entities.find(PriceSourceEntity.class,
				Objects.requireNonNull(sourceId, "sourceId"));
		if (source == null) {
			throw new PriceSourceNotFoundException();
		}
		if (source.kind != PriceSourceKind.OPERATOR_SCHEDULE) {
			throw invalid("GPU_PRICE_SCHEDULE_SOURCE_KIND_INVALID",
					"GPU price schedules require an operator-schedule Price Source");
		}
		return source;
	}

	private GpuPriceScheduleEntryEntity entry(UUID sourceId, UUID entryId) {
		requireOperatorSource(sourceId);
		GpuPriceScheduleEntryEntity entry = this.entities.find(GpuPriceScheduleEntryEntity.class,
				Objects.requireNonNull(entryId, "entryId"));
		if (entry == null || !entry.sourceId.equals(sourceId)) {
			throw new GpuPriceScheduleEntryNotFoundException();
		}
		return entry;
	}

	private void flushConstraints() {
		try {
			this.entities.flush();
		}
		catch (OptimisticLockException failure) {
			throw new GpuPriceScheduleRevisionConflictException();
		}
		catch (PersistenceException failure) {
			if (contains(failure, "ex_gpu_price_schedule_no_overlap")
					|| contains(failure, "GPU price schedule entries overlap")) {
				throw new GpuPriceScheduleOverlapException();
			}
			throw failure;
		}
	}

	private static boolean contains(Throwable failure, String text) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause.getMessage() != null && cause.getMessage().contains(text)) {
				return true;
			}
		}
		return false;
	}

	private static void requireRevision(GpuPriceScheduleEntryEntity entry, long expectedRevision) {
		if (entry.revision != expectedRevision) {
			throw new GpuPriceScheduleRevisionConflictException();
		}
	}

	private static void requirePositive(BigDecimal value, String code, String detail) {
		if (value == null || value.signum() <= 0) {
			throw invalid(code, detail);
		}
	}

	private static void requireNonNegative(BigDecimal value, String code, String detail) {
		if (value == null || value.signum() < 0) {
			throw invalid(code, detail);
		}
	}

	private static PriceSourceValidationException invalid(String code, String detail) {
		return new PriceSourceValidationException(code, detail);
	}

	private GpuPriceScheduleEntryView view(GpuPriceScheduleEntryEntity entry) {
		return entry.view();
	}

}

@FunctionalInterface
interface GpuPriceScheduleReader {

	Optional<GpuPriceScheduleEntryView> rate(GpuComputePriceQuery query);

}
