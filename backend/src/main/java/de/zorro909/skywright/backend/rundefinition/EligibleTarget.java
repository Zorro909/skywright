package de.zorro909.skywright.backend.rundefinition;

/** One supported target capability observation, not a live-capacity promise. */
public record EligibleTarget(String target, String targetClass, String acceleratorBackend, String gpuModel,
		int maximumGpuCount, long gpuMemoryBytes) {
}
