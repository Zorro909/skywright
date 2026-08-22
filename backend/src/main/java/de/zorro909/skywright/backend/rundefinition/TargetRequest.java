package de.zorro909.skywright.backend.rundefinition;

import java.math.BigDecimal;

import de.zorro909.skywright.backend.targetstorage.TargetClass;

/** Submission-time placement constraints retained without selecting infrastructure. */
public record TargetRequest(TargetClass targetClass, int gpuCount, Long minimumGpuMemoryBytes, String target,
		String gpuModel, BigDecimal minimumThroughput) {
}
