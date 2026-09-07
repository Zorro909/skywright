package de.zorro909.skywright.backend.runsubmission;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Evaluator-owned exposure evidence; delivery never calculates or chooses ceilings. */
public record CeilingStopDecision(UUID decisionId, UUID runId, Instant decidedAt, JsonNode ceilings,
		List<String> metConditions, JsonNode observedExposure, boolean estimateComplete, JsonNode sourceFreshness) {
	public CeilingStopDecision {
		if (decisionId == null || runId == null || decidedAt == null || ceilings == null
				|| (!ceilings.isObject() || ceilings.isEmpty()) || observedExposure == null
				|| (!observedExposure.isObject() || observedExposure.isEmpty()) || sourceFreshness == null
				|| (!sourceFreshness.isObject() || sourceFreshness.isEmpty()) || metConditions == null
				|| metConditions.isEmpty() || metConditions.size() > 2
				|| metConditions.stream().distinct().count() != metConditions.size()
				|| !List.of("runtime", "cost").containsAll(metConditions)
				|| metConditions.contains("cost") && !estimateComplete)
			throw new IllegalArgumentException("Incomplete Ceiling Stop Decision evidence");
		ceilings = ceilings.deepCopy();
		observedExposure = observedExposure.deepCopy();
		sourceFreshness = sourceFreshness.deepCopy();
		metConditions = metConditions.stream().sorted().toList();
	}

	@Override
	public JsonNode ceilings() {
		return ceilings.deepCopy();
	}

	@Override
	public JsonNode observedExposure() {
		return observedExposure.deepCopy();
	}

	@Override
	public JsonNode sourceFreshness() {
		return sourceFreshness.deepCopy();
	}
}
