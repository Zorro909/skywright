package de.zorro909.skywright.backend.gpuoffering;

import java.util.Arrays;

public enum GpuOfferingPurchaseMode {

	LOCAL("local"), ON_DEMAND("on-demand"), SPOT("spot");

	private final String wireValue;

	GpuOfferingPurchaseMode(String wireValue) {
		this.wireValue = wireValue;
	}

	public String wireValue() {
		return this.wireValue;
	}

	static GpuOfferingPurchaseMode fromWireValue(String value) {
		return Arrays.stream(values())
			.filter(candidate -> candidate.wireValue.equals(value))
			.findFirst()
			.orElseThrow(() -> new GpuOfferingValidationException("GPU_OFFERING_PURCHASE_MODE_INVALID",
					"Purchase mode is not supported"));
	}

}
