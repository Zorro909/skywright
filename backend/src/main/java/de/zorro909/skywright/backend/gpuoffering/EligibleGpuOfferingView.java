package de.zorro909.skywright.backend.gpuoffering;

import de.zorro909.skywright.backend.targetstorage.TargetClass;
import java.util.UUID;

public record EligibleGpuOfferingView(UUID id, long revision, TargetClass targetClass, String target,
		String providerOfferingId, String region, String instanceType, String gpuModel, int gpuCount,
		long gpuMemoryBytes, GpuOfferingPurchaseMode purchaseMode, TargetSupportTier supportTier) {
}
