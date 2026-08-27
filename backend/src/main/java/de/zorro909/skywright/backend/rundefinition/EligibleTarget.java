package de.zorro909.skywright.backend.rundefinition;

import de.zorro909.skywright.backend.targetstorage.TargetClass;

/** One supported target capability observation, not a live-capacity promise. */
public record EligibleTarget(String target, TargetClass targetClass, String acceleratorBackend, String gpuModel,
		int maximumGpuCount, long gpuMemoryBytes, EligibleTargetPrice price) {
}
