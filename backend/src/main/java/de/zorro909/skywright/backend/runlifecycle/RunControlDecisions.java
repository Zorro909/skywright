package de.zorro909.skywright.backend.runlifecycle;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read-through ownership seam for #65; accepted requests never establish an outcome. */
public interface RunControlDecisions {

	List<Decision> read(UUID runId);

	record Decision(UUID id, Kind kind, Instant decidedAt) {
	}

	enum Kind {

		CANCELLATION_REQUEST, CEILING_STOP

	}

}
