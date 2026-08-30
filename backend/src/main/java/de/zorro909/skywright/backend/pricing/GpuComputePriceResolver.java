package de.zorro909.skywright.backend.pricing;

import de.zorro909.skywright.backend.gpuoffering.EligibleGpuOfferingView;
import de.zorro909.skywright.backend.target.TargetIdentity;
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

	private final SkyPilotGpuComputePriceSource skyPilotCatalogue;

	GpuComputePriceResolver(EntityManager entities, OperatorGpuComputePriceSource operatorSchedules,
			SkyPilotGpuComputePriceSource skyPilotCatalogue) {
		this.entities = entities;
		this.operatorSchedules = operatorSchedules;
		this.skyPilotCatalogue = skyPilotCatalogue;
	}

	public BoundGpuComputePrice resolve(EligibleGpuOfferingView offering, Instant quoteTime) {
		PriceSourceBindingEntity binding = this.entities.find(PriceSourceBindingEntity.class,
				PriceSourceBindingKey.gpuCompute(new TargetIdentity(offering.target())).value());
		if (binding == null) {
			return unavailable(null, 0, null, null, null);
		}
		Duration maximumAge = Duration.parse(binding.maximumObservationAge);
		PriceSourceEntity source = this.entities.find(PriceSourceEntity.class, binding.sourceId);
		if (source == null || source.activeRevision == null || source.activeRevision != binding.sourceRevision) {
			return unavailable(binding.sourceId, binding.sourceRevision,
					source == null ? null : source.kind.wireValue(), maximumAge, null);
		}
		PriceSourceAssessmentValue assessment = latestSuccessfulAssessment(source, binding.sourceRevision);
		if (assessment == null || source.kind == PriceSourceKind.SKYPILOT_CATALOG
				&& !passed(assessment, offeringCapability(offering))) {
			return unavailable(binding.sourceId, binding.sourceRevision, source.kind.wireValue(), maximumAge, null);
		}
		GpuComputePriceQuery query = new GpuComputePriceQuery(binding.sourceId, binding.sourceRevision, offering.id(),
				offering.target(), offering.region(), offering.instanceType(), offering.gpuModel(), offering.gpuCount(),
				"spot".equals(offering.purchaseMode().wireValue()), quoteTime, maximumAge);
		GpuComputePriceResult result = switch (source.kind) {
			case OPERATOR_SCHEDULE -> this.operatorSchedules.price(query);
			case SKYPILOT_CATALOG -> this.skyPilotCatalogue.price(query);
			case PROVIDER_API -> GpuComputePriceResult.unavailable();
		};
		return new BoundGpuComputePrice(result, binding.sourceId, binding.sourceRevision, source.kind.wireValue(),
				maximumAge, assessment.observedFrom, assessment.observedUntil);
	}

	private static boolean passed(PriceSourceAssessmentValue assessment, String capability) {
		return assessment.capabilityResults.contains("passed:" + capability);
	}

	private static String offeringCapability(EligibleGpuOfferingView offering) {
		return "gpu-offering:" + offering.id() + ":revision:" + offering.revision();
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
