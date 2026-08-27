package de.zorro909.skywright.backend.gpuoffering;

import de.zorro909.skywright.backend.targetstorage.TargetClass;

public record EligibleGpuOfferingInput(TargetClass targetClass, String target, String providerOfferingId, String region,
		String instanceType, String gpuModel, int gpuCount, long gpuMemoryBytes, GpuOfferingPurchaseMode purchaseMode,
		TargetSupportTier supportTier) {
}
