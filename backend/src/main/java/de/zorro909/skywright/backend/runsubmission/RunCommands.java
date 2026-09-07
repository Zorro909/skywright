package de.zorro909.skywright.backend.runsubmission;

import de.zorro909.skywright.backend.runlifecycle.RunLifecycleReads;
import de.zorro909.skywright.backend.runlifecycle.RunLifecycleView;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Public control intent and source-backed command reads share one delivery path. */
@Service
public final class RunCommands {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final RunAcceptanceStore runs;

	private final RunCommandStore commands;

	private final RunCommandDelivery delivery;

	private final RunLifecycleReads lifecycle;

	RunCommands(RunAcceptanceStore runs, RunCommandStore commands, RunCommandDelivery delivery,
			RunLifecycleReads lifecycle) {
		this.runs = runs;
		this.commands = commands;
		this.delivery = delivery;
		this.lifecycle = lifecycle;
	}

	public RunCommand cancel(UUID runId, UUID requestId) {
		var command = commands.accept(runId, requestId, RunCommand.Kind.CANCELLATION_REQUEST, "{}");
		deliver(command);
		return command;
	}

	/** Called by the ceiling evaluator only after it has made its complete decision. */
	public RunCommand ceilingStop(CeilingStopDecision decision) {
		var policy = runs.get(decision.runId()).definition().value().path("executionPolicy");
		var expected = JSON.createObjectNode();
		if (policy.has("runtimeCeiling"))
			expected.set("runtimeCeiling", policy.path("runtimeCeiling"));
		if (policy.has("costCeiling"))
			expected.set("costCeiling", policy.path("costCeiling"));
		if (!expected.equals(decision.ceilings())
				|| decision.metConditions().contains("runtime") && !expected.has("runtimeCeiling")
				|| decision.metConditions().contains("cost") && !expected.has("costCeiling"))
			throw new RunSubmissionException("CEILING_DECISION_POLICY_MISMATCH", 422);
		var command = commands.accept(decision.runId(), decision.decisionId(), RunCommand.Kind.CEILING_STOP,
				JSON.writeValueAsString(decision));
		deliver(command);
		return command;
	}

	private void deliver(RunCommand command) {
		try {
			delivery.enqueue(command.id());
		}
		catch (java.util.concurrent.RejectedExecutionException full) {
			/* The durable due record remains discoverable. */ }
	}

	public record Read(RunCommand command, RunLifecycleView lifecycle) {
	}

	public Read read(UUID runId, UUID commandId) {
		var command = commands.get(runId, commandId);
		return new Read(command, lifecycle.read(runId).lifecycle());
	}

}
