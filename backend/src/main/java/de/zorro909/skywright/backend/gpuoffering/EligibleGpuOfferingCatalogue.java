package de.zorro909.skywright.backend.gpuoffering;

import de.zorro909.skywright.backend.rundefinition.TargetRequest;
import de.zorro909.skywright.backend.targetstorage.TargetClass;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class EligibleGpuOfferingCatalogue {

	private final EntityManager entities;

	EligibleGpuOfferingCatalogue(EntityManager entities) {
		this.entities = entities;
	}

	public EligibleGpuOfferingView create(EligibleGpuOfferingInput input) {
		validate(input);
		EligibleGpuOfferingEntity offering = EligibleGpuOfferingEntity.create(UUID.randomUUID(), input);
		this.entities.persist(offering);
		return offering.view();
	}

	@Transactional(readOnly = true)
	public EligibleGpuOfferingView get(UUID id) {
		return offering(id).view();
	}

	@Transactional(readOnly = true)
	public List<EligibleGpuOfferingView> list() {
		return entities().stream().map(EligibleGpuOfferingEntity::view).toList();
	}

	public EligibleGpuOfferingView update(UUID id, long expectedRevision, EligibleGpuOfferingInput input) {
		validate(input);
		EligibleGpuOfferingEntity offering = offering(id);
		requireRevision(offering, expectedRevision);
		offering.replace(input);
		offering.revision++;
		return offering.view();
	}

	public void delete(UUID id, long expectedRevision) {
		EligibleGpuOfferingEntity offering = offering(id);
		requireRevision(offering, expectedRevision);
		this.entities.remove(offering);
	}

	@Transactional(readOnly = true)
	public List<EligibleGpuOfferingView> eligible(TargetRequest request) {
		if (request == null || request.targetClass() == null || request.gpuCount() <= 0) {
			return List.of();
		}
		return list().stream().filter(offering -> matches(offering, request)).toList();
	}

	@Transactional(readOnly = true)
	public List<EligibleGpuOfferingView> admissibleForTarget(String target) {
		if (target == null) {
			return List.of();
		}
		return list().stream()
			.filter(EligibleGpuOfferingCatalogue::isAdmissiblePair)
			.filter(offering -> target.equals(offering.target()))
			.toList();
	}

	static boolean matches(EligibleGpuOfferingView offering, TargetRequest request) {
		return request.gpuCount() > 0 && isAdmissiblePair(offering) && offering.targetClass() == request.targetClass()
				&& offering.gpuCount() >= request.gpuCount()
				&& (request.minimumGpuMemoryBytes() == null
						|| offering.gpuMemoryBytes() >= request.minimumGpuMemoryBytes())
				&& (request.target() == null || offering.target().equals(request.target()))
				&& (request.gpuModel() == null || offering.gpuModel().equals(request.gpuModel()));
	}

	private static boolean isAdmissiblePair(EligibleGpuOfferingView offering) {
		return offering.supportTier() != TargetSupportTier.DEFERRED
				&& offering.purchaseMode() == requiredPurchaseMode(offering.targetClass());
	}

	private static GpuOfferingPurchaseMode requiredPurchaseMode(TargetClass targetClass) {
		return switch (targetClass) {
			case LOCAL_SINGLE_GPU, LOCAL_MULTI_GPU -> GpuOfferingPurchaseMode.LOCAL;
			case CLOUD_ON_DEMAND -> GpuOfferingPurchaseMode.ON_DEMAND;
			case CLOUD_SPOT -> GpuOfferingPurchaseMode.SPOT;
		};
	}

	private List<EligibleGpuOfferingEntity> entities() {
		return this.entities
			.createQuery("select offering from EligibleGpuOfferingEntity offering order by offering.target, "
					+ "offering.region, offering.instanceType, offering.id", EligibleGpuOfferingEntity.class)
			.getResultList();
	}

	private EligibleGpuOfferingEntity offering(UUID id) {
		EligibleGpuOfferingEntity result = this.entities.find(EligibleGpuOfferingEntity.class,
				Objects.requireNonNull(id, "offeringId"));
		if (result == null) {
			throw new GpuOfferingNotFoundException();
		}
		return result;
	}

	private static void requireRevision(EligibleGpuOfferingEntity offering, long expectedRevision) {
		if (offering.revision != expectedRevision) {
			throw new GpuOfferingConflictException();
		}
	}

	private static void validate(EligibleGpuOfferingInput input) {
		if (input == null || input.targetClass() == null || input.purchaseMode() == null
				|| input.supportTier() == null) {
			throw new GpuOfferingValidationException("GPU_OFFERING_INVALID", "Required offering facts are missing");
		}
		requireText(input.target(), "Target");
		requireText(input.providerOfferingId(), "Provider offering identity");
		requireText(input.region(), "Region");
		requireText(input.instanceType(), "Instance type");
		requireText(input.gpuModel(), "GPU model");
		if (input.gpuCount() <= 0) {
			throw new GpuOfferingValidationException("GPU_OFFERING_GPU_COUNT_INVALID", "GPU count must be positive");
		}
		if (input.gpuMemoryBytes() <= 0) {
			throw new GpuOfferingValidationException("GPU_OFFERING_GPU_MEMORY_INVALID",
					"Per-GPU memory must be positive");
		}
	}

	private static void requireText(String value, String name) {
		if (value == null || value.isBlank() || value.length() > 255) {
			throw new GpuOfferingValidationException("GPU_OFFERING_INVALID",
					name + " must contain between 1 and 255 characters");
		}
	}

}
