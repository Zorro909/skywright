package de.zorro909.skywright.backend.gpuoffering;

import de.zorro909.skywright.backend.targetstorage.TargetClass;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;

@Entity(name = "EligibleGpuOfferingEntity")
@Table(name = "eligible_gpu_offering")
class EligibleGpuOfferingEntity {

	@Id
	UUID id;

	@Column(nullable = false)
	long revision;

	@Enumerated(EnumType.STRING)
	@Column(name = "target_class", nullable = false)
	TargetClass targetClass;

	@Column(nullable = false)
	String target;

	@Column(name = "provider_offering_id", nullable = false)
	String providerOfferingId;

	@Column(nullable = false)
	String region;

	@Column(name = "instance_type", nullable = false)
	String instanceType;

	@Column(name = "gpu_model", nullable = false)
	String gpuModel;

	@Column(name = "gpu_count", nullable = false)
	int gpuCount;

	@Column(name = "gpu_memory_bytes", nullable = false)
	long gpuMemoryBytes;

	@Enumerated(EnumType.STRING)
	@Column(name = "purchase_mode", nullable = false)
	GpuOfferingPurchaseMode purchaseMode;

	@Enumerated(EnumType.STRING)
	@Column(name = "support_tier", nullable = false)
	TargetSupportTier supportTier;

	@Version
	@Column(name = "persistence_version", nullable = false)
	long persistenceVersion;

	protected EligibleGpuOfferingEntity() {
	}

	static EligibleGpuOfferingEntity create(UUID id, EligibleGpuOfferingInput input) {
		EligibleGpuOfferingEntity result = new EligibleGpuOfferingEntity();
		result.id = id;
		result.revision = 1;
		result.replace(input);
		return result;
	}

	void replace(EligibleGpuOfferingInput input) {
		this.targetClass = input.targetClass();
		this.target = input.target();
		this.providerOfferingId = input.providerOfferingId();
		this.region = input.region();
		this.instanceType = input.instanceType();
		this.gpuModel = input.gpuModel();
		this.gpuCount = input.gpuCount();
		this.gpuMemoryBytes = input.gpuMemoryBytes();
		this.purchaseMode = input.purchaseMode();
		this.supportTier = input.supportTier();
	}

	EligibleGpuOfferingView view() {
		return new EligibleGpuOfferingView(this.id, this.revision, this.targetClass, this.target,
				this.providerOfferingId, this.region, this.instanceType, this.gpuModel, this.gpuCount,
				this.gpuMemoryBytes, this.purchaseMode, this.supportTier);
	}

}
