package de.zorro909.skywright.backend.pricing;

/**
 * Price-source outcome plus the exact binding and assessment evidence used to obtain it.
 */
public record BoundGpuComputePrice(GpuComputePriceResult result, PriceSourceEvidence evidence) {

	public BoundGpuComputePrice(GpuComputePriceResult result, java.util.UUID sourceId, long sourceRevision,
			String sourceKind, java.time.Duration maximumObservationAge, java.time.Instant sourceObservedFrom,
			java.time.Instant sourceObservedUntil) {
		this(result, new PriceSourceEvidence(sourceId, sourceRevision, sourceKind, maximumObservationAge,
				sourceObservedFrom, sourceObservedUntil));
	}

	public java.util.UUID sourceId() {
		return this.evidence.sourceId();
	}

	public long sourceRevision() {
		return this.evidence.sourceRevision();
	}

	public String sourceKind() {
		return this.evidence.sourceKind();
	}

	public java.time.Duration maximumObservationAge() {
		return this.evidence.maximumObservationAge();
	}

	public java.time.Instant sourceObservedFrom() {
		return this.evidence.sourceObservedFrom();
	}

	public java.time.Instant sourceObservedUntil() {
		return this.evidence.sourceObservedUntil();
	}

}
