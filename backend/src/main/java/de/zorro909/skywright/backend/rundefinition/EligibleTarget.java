package de.zorro909.skywright.backend.rundefinition;

import de.zorro909.skywright.backend.targetstorage.TargetClass;
import java.util.UUID;

/** One supported target capability observation, not a live-capacity promise. */
public record EligibleTarget(UUID offeringId, String target, String providerOfferingId, String region,
		String instanceType, TargetClass targetClass, String acceleratorBackend, String gpuModel, int maximumGpuCount,
		long gpuMemoryBytes, String purchaseMode, String supportTier, EligibleTargetPrice price) {

	public EligibleTarget(String target, TargetClass targetClass, String acceleratorBackend, String gpuModel,
			int maximumGpuCount, long gpuMemoryBytes, EligibleTargetPrice price) {
		this(null, target, null, null, null, targetClass, acceleratorBackend, gpuModel, maximumGpuCount, gpuMemoryBytes,
				null, null, price);
	}

}
