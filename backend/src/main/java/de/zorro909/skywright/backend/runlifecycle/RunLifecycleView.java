package de.zorro909.skywright.backend.runlifecycle;

import de.zorro909.skywright.backend.orchestration.RetainedSkyPilotFact;
import java.time.Instant;
import java.util.List;

public record RunLifecycleView(String state, boolean terminalLatched, String cause, String sourceAvailability,
		String processAvailability, Instant fetchedAt, Instant skyPilotReadAt, Instant runStoreReadAt,
		LastSeen lastSeen, List<String> evidenceGaps, List<RetainedSkyPilotFact> facts,
		List<RunLifecycleDerivation.Conflict> conflicts, List<RunControlDecisions.Decision> controlDecisions) {

	public RunLifecycleView {
		evidenceGaps = List.copyOf(evidenceGaps);
		facts = List.copyOf(facts);
		conflicts = List.copyOf(conflicts);
		controlDecisions = List.copyOf(controlDecisions);
	}

	public record LastSeen(String state, Instant observedAt, long ageMillis) {
	}
}
