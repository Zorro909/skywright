package de.zorro909.skywright.backend.runsubmission;

import org.springframework.boot.context.properties.ConfigurationProperties;
import de.zorro909.skywright.backend.runtimeassembly.LocalRuntimeProjection;

/** Operator-qualified local target; absent configuration means unavailable admission. */
@ConfigurationProperties(prefix = "skywright.local-run", ignoreUnknownFields = false)
public record LocalRunTargetSettings(String identity, String kubernetesContext, String gpuModel, int maximumGpuCount,
		long gpuMemoryBytes, String cpus, String memory, boolean writerAuthorityEnabled) {
	LocalRuntimeProjection.Target target(String requested) {
		if (identity == null)
			throw new RunSubmissionException("LOCAL_TARGET_UNAVAILABLE", 503);
		if (!identity.equals(requested))
			throw new RunSubmissionException("LOCAL_TARGET_INELIGIBLE", 422);
		try {
			return new LocalRuntimeProjection.Target(identity, kubernetesContext, gpuModel, maximumGpuCount,
					gpuMemoryBytes, cpus, memory);
		}
		catch (IllegalArgumentException failure) {
			throw new RunSubmissionException("LOCAL_TARGET_UNAVAILABLE", 503);
		}
	}
}
