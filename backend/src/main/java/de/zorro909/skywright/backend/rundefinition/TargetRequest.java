package de.zorro909.skywright.backend.rundefinition;

import java.math.BigDecimal;

/** Submission-time placement constraints retained without selecting infrastructure. */
public record TargetRequest(String targetClass, int gpuCount, Long minimumGpuMemoryBytes, String target,
		String gpuModel, BigDecimal minimumThroughput) {
}
