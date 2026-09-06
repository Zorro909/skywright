package de.zorro909.skywright.backend.runstore;

import java.time.Instant;

/** One adapter operation, identified by producerId and requestNumber. */
public record RunStoreOperationMeasurement(String operation, long bytes, String direction, long requestNumber,
		String runId, Instant timestamp, String provenance, boolean succeeded, String producerId) {
}
