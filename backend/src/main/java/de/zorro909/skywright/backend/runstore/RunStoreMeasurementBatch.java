package de.zorro909.skywright.backend.runstore;

import java.time.Instant;
import java.util.List;

/** Bounded delivery with explicit unknown usage for any evicted request identities. */
public record RunStoreMeasurementBatch(String producerId, String runId, String provenance, long throughSequence,
		List<RunStoreOperationMeasurement> measurements, Gap gap) {

	public RunStoreMeasurementBatch {
		measurements = List.copyOf(measurements);
	}

	/** Inclusive missing sequence range; its usage must never be treated as zero. */
	public record Gap(long firstSequence, long lastSequence, Instant earliestTimestamp, Instant latestTimestamp) {

		public long count() {
			return this.lastSequence - this.firstSequence + 1;
		}

	}

}
