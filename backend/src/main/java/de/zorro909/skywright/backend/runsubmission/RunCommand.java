package de.zorro909.skywright.backend.runsubmission;

import java.time.Instant;
import java.util.UUID;

/** Durable intent and delivery progress, never a Run Lifecycle State. */
public record RunCommand(UUID id, UUID runId, Kind kind, Instant acceptedAt, String evidence, String disposition,
		Instant projectedAt, Instant forceAfter, Instant nextAttemptAt, UUID lease, int attempts) {
	public enum Kind {

		SUBMISSION, CANCELLATION_REQUEST, CEILING_STOP

	}
}
