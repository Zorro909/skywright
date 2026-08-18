package de.zorro909.skywright.backend.runstore;

import java.time.Instant;

/** One non-secret provider request measurement retained for later usage attribution. */
public record RunStoreOperationMeasurement(String operation, long bytes, String direction, long requestNumber,
		String runId, Instant timestamp, String provenance, boolean succeeded) {
}
