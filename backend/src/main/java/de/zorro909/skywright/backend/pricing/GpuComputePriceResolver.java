package de.zorro909.skywright.backend.pricing;

import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves the explicitly bound GPU-compute source without fallback selection. */
@Service
@Transactional(readOnly = true)
public class GpuComputePriceResolver {

	private final EntityManager entities;

	private final OperatorGpuComputePriceSource operatorSchedules;

	GpuComputePriceResolver(EntityManager entities, OperatorGpuComputePriceSource operatorSchedules) {
		this.entities = entities;
		this.operatorSchedules = operatorSchedules;
	}

	public BoundGpuComputePrice resolve(String target, UUID offeringId, Instant quoteTime) {
		PriceSourceBindingEntity binding = this.entities.find(PriceSourceBindingEntity.class,
				"target:" + target + ":resource:gpu-compute");
		if (binding == null) {
			return unavailable(null, 0, null, null, null);
		}
		Duration maximumAge = Duration.parse(binding.maximumObservationAge);
		PriceSourceEntity source = this.entities.find(PriceSourceEntity.class, binding.sourceId);
		if (source == null || source.activeRevision == null || source.activeRevision != binding.sourceRevision) {
			return unavailable(binding.sourceId, binding.sourceRevision, source == null ? null : source.kind,
					maximumAge, null);
		}
		PriceSourceAssessmentValue assessment = latestSuccessfulAssessment(source, binding.sourceRevision);
		if (assessment == null) {
			return unavailable(binding.sourceId, binding.sourceRevision, source.kind, maximumAge, null);
		}
		GpuComputePriceResult result = "operator-schedule".equals(source.kind) ? this.operatorSchedules.price(
				new GpuComputePriceQuery(binding.sourceId, binding.sourceRevision, offeringId, quoteTime, maximumAge))
				: GpuComputePriceResult.unavailable();
		return new BoundGpuComputePrice(result, binding.sourceId, binding.sourceRevision, source.kind, maximumAge,
				assessment.observedFrom, assessment.observedUntil);
	}

	private static PriceSourceAssessmentValue latestSuccessfulAssessment(PriceSourceEntity source, long revision) {
		List<PriceSourceAssessmentValue> matching = source.assessments.stream()
			.filter(assessment -> assessment.revision == revision && assessment.successful)
			.toList();
		return matching.isEmpty() ? null : matching.getLast();
	}

	private static BoundGpuComputePrice unavailable(UUID sourceId, long sourceRevision, String sourceKind,
			Duration maximumAge, PriceSourceAssessmentValue assessment) {
		return new BoundGpuComputePrice(GpuComputePriceResult.unavailable(), sourceId, sourceRevision, sourceKind,
				maximumAge, assessment == null ? null : assessment.observedFrom,
				assessment == null ? null : assessment.observedUntil);
	}

}
