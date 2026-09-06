package de.zorro909.skywright.backend.orchestration;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * Provenance-isolated durable sink. Insert absent (Run, kind, source event) keys; retain
 * conflicting payloads separately with observation times per ADR 0005. A successful
 * completion acknowledges durable retention, not a lifecycle update. Database
 * implementation belongs to the Run-record/lifecycle milestone.
 */
@FunctionalInterface
public interface RetainedSkyPilotFacts {

	CompletionStage<Void> append(List<RetainedSkyPilotFact> facts);

}
