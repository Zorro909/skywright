package de.zorro909.skywright.backend.pricing;

public record GpuComputePriceResult(Outcome outcome, GpuComputeRate rate, String code, String detail) {

	public enum Outcome {

		AVAILABLE, UNAVAILABLE, MISSING, STALE

	}

	public static GpuComputePriceResult available(GpuComputeRate rate) {
		return new GpuComputePriceResult(Outcome.AVAILABLE, rate, null, null);
	}

	public static GpuComputePriceResult unavailable() {
		return new GpuComputePriceResult(Outcome.UNAVAILABLE, null, "PRICE_SOURCE_UNAVAILABLE",
				"The Price Source could not be read");
	}

	public static GpuComputePriceResult missing() {
		return new GpuComputePriceResult(Outcome.MISSING, null, "GPU_COMPUTE_PRICE_MISSING",
				"No GPU-compute rate covers the quote time");
	}

	public static GpuComputePriceResult stale(GpuComputeRate rate) {
		return new GpuComputePriceResult(Outcome.STALE, rate, "GPU_COMPUTE_PRICE_STALE",
				"The GPU-compute rate observation is older than the binding permits");
	}

}
