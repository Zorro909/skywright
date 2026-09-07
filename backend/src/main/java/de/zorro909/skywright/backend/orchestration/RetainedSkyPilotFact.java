package de.zorro909.skywright.backend.orchestration;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** One immutable source event; observedAt resolves conflicts but is never in the key. */
public record RetainedSkyPilotFact(UUID runId, Kind kind, String sourceEventIdentity, Map<String, String> payload,
		Instant observedAt, boolean completeUniqueObservation) {

	public RetainedSkyPilotFact(UUID runId, Kind kind, String sourceEventIdentity, Map<String, String> payload,
			Instant observedAt) {
		this(runId, kind, sourceEventIdentity, payload, observedAt, true);
	}

	public RetainedSkyPilotFact {
		java.util.Objects.requireNonNull(runId);
		java.util.Objects.requireNonNull(kind);
		java.util.Objects.requireNonNull(observedAt);
		if (sourceEventIdentity == null || sourceEventIdentity.isBlank())
			throw new IllegalArgumentException("Source event identity is required");
		payload = Map.copyOf(payload);
	}
	public enum Kind {

		SUBMISSION_OPERATION_FAILURE, SUBMISSION, EXECUTION_STARTED, INFRASTRUCTURE, RECOVERY, TERMINATION

	}
}
