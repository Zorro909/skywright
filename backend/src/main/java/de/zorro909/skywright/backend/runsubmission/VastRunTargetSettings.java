package de.zorro909.skywright.backend.runsubmission;

import de.zorro909.skywright.backend.runtimeassembly.VastRuntimeProjection;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment constraints are not evidence that Vast has passed qualification. */
@ConfigurationProperties(prefix = "skywright.vast-run", ignoreUnknownFields = false)
public record VastRunTargetSettings(String region, String instanceType, String gpuModel, long gpuMemoryBytes,
		String cpus, String memory, int diskSize, BigDecimal maxHourlyCost, BigDecimal maxBidHourlyCost) {

	VastRuntimeProjection.Target target() {
		return target(false);
	}

	VastRuntimeProjection.Target target(boolean spot) {
		try {
			if (spot && maxBidHourlyCost == null)
				throw new IllegalArgumentException("An explicit Vast bid is required");
			return new VastRuntimeProjection.Target(region, instanceType, gpuModel, gpuMemoryBytes, cpus, memory,
					diskSize, maxHourlyCost, spot ? maxBidHourlyCost : null);
		}
		catch (IllegalArgumentException failure) {
			throw new RunSubmissionException("VAST_TARGET_UNAVAILABLE", 503);
		}
	}

}
