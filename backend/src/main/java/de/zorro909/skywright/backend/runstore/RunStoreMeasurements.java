package de.zorro909.skywright.backend.runstore;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;

/** Storage never waits for a diagnostic consumer; overflow remains explicit. */
final class RunStoreMeasurements {

	private final String producerId = UUID.randomUUID().toString();

	private final String runId;

	private final String provenance;

	private final int capacity;

	private final ArrayDeque<RunStoreOperationMeasurement> records = new ArrayDeque<>();

	private long sequence;

	private RunStoreMeasurementBatch.Gap gap;

	RunStoreMeasurements(String runId, String provenance, int capacity) {
		if (capacity < 1) {
			throw new IllegalArgumentException("measurement capacity must be positive");
		}
		this.runId = runId;
		this.provenance = provenance;
		this.capacity = capacity;
	}

	synchronized void record(String operation, long bytes, String direction, Instant timestamp, boolean succeeded) {
		if (this.records.size() == this.capacity) {
			var evicted = this.records.removeFirst();
			this.gap = new RunStoreMeasurementBatch.Gap(
					this.gap == null ? evicted.requestNumber() : this.gap.firstSequence(), evicted.requestNumber(),
					this.gap == null || evicted.timestamp().isBefore(this.gap.earliestTimestamp()) ? evicted.timestamp()
							: this.gap.earliestTimestamp(),
					this.gap == null || evicted.timestamp().isAfter(this.gap.latestTimestamp()) ? evicted.timestamp()
							: this.gap.latestTimestamp());
		}
		this.records.addLast(new RunStoreOperationMeasurement(operation, bytes, direction, ++this.sequence, this.runId,
				timestamp, this.provenance, succeeded, this.producerId));
	}

	synchronized List<RunStoreOperationMeasurement> snapshot() {
		return List.copyOf(this.records);
	}

	synchronized RunStoreMeasurementBatch drain() {
		var batch = new RunStoreMeasurementBatch(this.producerId, this.runId, this.provenance, this.sequence,
				List.copyOf(this.records), this.gap);
		this.records.clear();
		this.gap = null;
		return batch;
	}

}
