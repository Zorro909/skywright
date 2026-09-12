package de.zorro909.skywright.backend.runsubmission;

import de.zorro909.skywright.backend.runtimeassembly.VastRuntimeProjection;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment constraints are not evidence that Vast has passed qualification. */
@ConfigurationProperties(prefix = "skywright.vast-run", ignoreUnknownFields = false)
public record VastRunTargetSettings(String region, String instanceType, String gpuModel, long gpuMemoryBytes,
		String cpus, String memory, int diskSize, BigDecimal maxHourlyCost) {

	VastRuntimeProjection.Target target() {
		try {
			return new VastRuntimeProjection.Target(region, instanceType, gpuModel, gpuMemoryBytes, cpus, memory,
					diskSize, maxHourlyCost);
		}
		catch (IllegalArgumentException failure) {
			throw new RunSubmissionException("VAST_TARGET_UNAVAILABLE", 503);
		}
	}

}
