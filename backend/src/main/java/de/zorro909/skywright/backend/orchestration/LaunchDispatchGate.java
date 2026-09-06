package de.zorro909.skywright.backend.orchestration;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Atomic durable first-dispatch authority supplied by command delivery (#65). Commit the
 * claim before completing FIRST_DISPATCH. Never expire or release it because an SDK
 * response, request identifier or job lookup is missing. This stores Skywright intent,
 * never an Orchestrator Operation.
 */
@FunctionalInterface
public interface LaunchDispatchGate {

	CompletionStage<Decision> claim(UUID runId, String taskFingerprint);

	enum Decision {

		FIRST_DISPATCH, ALREADY_DISPATCHED, DEFINITION_CONFLICT, UNAVAILABLE

	}

}
