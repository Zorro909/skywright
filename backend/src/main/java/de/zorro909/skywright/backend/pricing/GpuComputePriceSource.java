package de.zorro909.skywright.backend.pricing;

@FunctionalInterface
public interface GpuComputePriceSource {

	GpuComputePriceResult price(GpuComputePriceQuery query);

}
