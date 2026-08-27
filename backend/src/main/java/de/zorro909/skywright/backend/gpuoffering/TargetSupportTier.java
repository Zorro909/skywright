package de.zorro909.skywright.backend.gpuoffering;

import java.util.Arrays;

public enum TargetSupportTier {

	FIRST_CLASS("first-class"), COMPATIBLE("compatible"), DEFERRED("deferred");

	private final String wireValue;

	TargetSupportTier(String wireValue) {
		this.wireValue = wireValue;
	}

	public String wireValue() {
		return this.wireValue;
	}

	static TargetSupportTier fromWireValue(String value) {
		return Arrays.stream(values())
			.filter(candidate -> candidate.wireValue.equals(value))
			.findFirst()
			.orElseThrow(() -> new GpuOfferingValidationException("GPU_OFFERING_SUPPORT_TIER_INVALID",
					"Target Support Tier is not supported"));
	}

}
