package de.zorro909.skywright.backend.runsubmission;

import de.zorro909.skywright.backend.orchestration.Orchestrator;
import de.zorro909.skywright.backend.orchestration.RunJobAdapter;
import de.zorro909.skywright.backend.runlifecycle.RunLifecycleReads;
import de.zorro909.skywright.backend.runlifecycle.RunLifecycleDerivation;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** Resumes accepted commands. Only the existing first-dispatch gate can launch. */
@Service
public final class RunCommandDelivery {

	private final RunAcceptanceStore runs;

	private final RunCommandStore commands;

	private final RunLaunchDelivery launches;

	private final RunLifecycleReads lifecycle;

	private final RunStopRequests requests;

	private final RunJobAdapter jobs;

	private final Orchestrator orchestrator;

	private final Clock clock;

	private final Executor executor;

	RunCommandDelivery(RunAcceptanceStore runs, RunCommandStore commands, RunLaunchDelivery launches,
			RunLifecycleReads lifecycle, RunStopRequests requests, RunJobAdapter jobs, Orchestrator orchestrator,
			Clock clock, @Qualifier("runCommandExecutor") Executor executor) {
		this.runs = runs;
		this.commands = commands;
		this.launches = launches;
		this.lifecycle = lifecycle;
		this.requests = requests;
		this.jobs = jobs;
		this.orchestrator = orchestrator;
		this.clock = clock;
		this.executor = executor;
	}

	CompletionStage<RunJobAdapter.Submission> submit(AcceptedRun run, RunAdmission.Prepared prepared) {
		var completion = new CompletableFuture<RunJobAdapter.Submission>();
		try {
			executor.execute(() -> {
				try {
					var command = commands.claim(run.submissionId());
					if (command == null) {
						prepared.close();
						jobs.reconcile(run.runId()).whenComplete((value, error) -> {
							if (error != null)
								completion.completeExceptionally(error);
							else
								completion.complete(new RunJobAdapter.Submission.Rediscovered(value));
						});
						return;
					}
					launch(command, run, prepared).whenComplete((value, error) -> {
						if (error != null)
							completion.completeExceptionally(error);
						else
							completion.complete(value);
					});
				}
				catch (RuntimeException failure) {
					prepared.close();
					completion.completeExceptionally(failure);
				}
			});
		}
		catch (RuntimeException unavailable) {
			prepared.close();
			completion.completeExceptionally(unavailable);
		}
		return completion;
	}

	public void enqueue(UUID commandId) {
		executor.execute(() -> reconcile(commandId));
	}

	public void reconcile(UUID commandId) {
		RunCommand command = commands.claim(commandId);
		if (command == null)
			return;
		try {
			var run = runs.get(command.runId());
			if (command.kind() == RunCommand.Kind.SUBMISSION) {
				if (!commands.submissionClaimed(run.runId()) && !runs.dispatchPrevented(run.runId())
						&& !orchestrator.refreshAvailability()
							.toCompletableFuture()
							.get(5, TimeUnit.SECONDS)
							.available()) {
					commands.finish(command, "delivery-unavailable", true);
					return;
				}
				launch(command, run, null);
				return;
			}
			if (runs.dispatchPrevented(run.runId())) {
				commands.finish(command, "dispatch-prevented", false);
				return;
			}
			var observed = lifecycle.read(run).lifecycle();
			if (observed.state() != null
					&& java.util.Set.of("finished", "failed", "cancelled").contains(observed.state())) {
				boolean effected = (command.projectedAt() != null || command.stopAttemptedAt() != null)
						&& "cancelled".equals(observed.state())
						&& stopEffect(command.kind(), observed.cause(), observed.controlDecisions());
				commands.finish(command, effected ? "effect-observed" : "no-stop-effected", false);
				return;
			}
			command = commands.attemptStop(command);
			if (command == null)
				return;
			RuntimeException projectionFailure = null;
			if (command.projectedAt() == null) {
				try {
					command = commands.projected(command, requests.deliver(run, command));
					if (command == null)
						return;
				}
				catch (RuntimeException failure) {
					projectionFailure = failure;
				}
			}
			if (command.forceAfter() != null && !clock.instant().isBefore(command.forceAfter())) {
				var accepted = jobs.cancel(run.runId()).toCompletableFuture().get(5, TimeUnit.SECONDS);
				if (accepted.failure() == null) {
					jobs.complete(run.runId(), accepted.value());
					commands.finish(command, "force-accepted", true);
				}
				else
					commands.finish(command, "force-uncertain", true);
			}
			else
				commands.finish(command, projectionFailure == null ? "projection-delivered" : "delivery-unavailable",
						true);
		}
		catch (Exception failure) {
			if (failure instanceof InterruptedException)
				Thread.currentThread().interrupt();
			commands.finish(command, "delivery-unavailable", true);
		}
	}

	static boolean stopEffect(RunCommand.Kind kind, String cause,
			java.util.List<de.zorro909.skywright.backend.runlifecycle.RunControlDecisions.Decision> decisions) {
		if (kind == RunCommand.Kind.CANCELLATION_REQUEST)
			return !"policy_stopped".equals(cause);
		return kind == RunCommand.Kind.CEILING_STOP && ("policy_stopped".equals(cause) || cause == null && decisions
			.stream()
			.noneMatch(d -> d
				.kind() == de.zorro909.skywright.backend.runlifecycle.RunControlDecisions.Kind.CANCELLATION_REQUEST));
	}

	private CompletionStage<RunJobAdapter.Submission> launch(RunCommand command, AcceptedRun run,
			RunAdmission.Prepared prepared) {
		try {
			return launches.deliver(run, prepared).whenComplete((result, failure) -> {
				if (failure != null) {
					commands.finish(command, "delivery-unavailable", true);
					return;
				}
				if (result instanceof RunJobAdapter.Submission.Initiated initiated) {
					// Completion stays ephemeral; covered observations are retained
					// even when the original caller has stopped waiting.
					jobs.complete(run.runId(), initiated.operation());
					commands.finish(command, "source-accepted", true);
				}
				else if (result instanceof RunJobAdapter.Submission.Rediscovered found) {
					boolean observed = found.observation().availability() == RunJobAdapter.SourceAvailability.LIVE
							|| new RunLifecycleDerivation().terminalRetention(runs.retainedFacts(run.runId()));
					commands.finish(command, observed ? "source-observed" : "handoff-uncertain", !observed);
				}
				else if (result instanceof RunJobAdapter.Submission.Refused refused
						&& refused.code().equals("RUN_STOP_REQUESTED"))
					commands.finish(command, "dispatch-prevented", false);
				else
					commands.finish(command, "handoff-uncertain", true);
			});
		}
		catch (RuntimeException failure) {
			commands.finish(command, "delivery-unavailable", true);
			return CompletableFuture.failedFuture(failure);
		}
	}

}
