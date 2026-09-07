package de.zorro909.skywright.backend.runsubmission;

import de.zorro909.skywright.backend.credential.LocalCredentialProjections;
import de.zorro909.skywright.backend.orchestration.RunJobAdapter;
import java.nio.file.Path;
import java.util.concurrent.CompletionStage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** Reconstructs exact pre-dispatch credentials; claimed launches only rediscover. */
@Service
final class RunLaunchDelivery {

	private final RunCommandStore commands;

	private final RunJobAdapter jobs;

	private final ObjectProvider<LocalCredentialProjections> broker;

	private final ObjectProvider<RuntimePullDelivery> pulls;

	RunLaunchDelivery(RunCommandStore commands, RunJobAdapter jobs, ObjectProvider<LocalCredentialProjections> broker,
			ObjectProvider<RuntimePullDelivery> pulls) {
		this.commands = commands;
		this.jobs = jobs;
		this.broker = broker;
		this.pulls = pulls;
	}

	CompletionStage<RunJobAdapter.Submission> deliver(AcceptedRun run, LocalRunAdmission.Prepared supplied) {
		if (commands.read(run.runId())
			.stream()
			.anyMatch(de.zorro909.skywright.backend.runlifecycle.RunControlDecisions.Decision::dispatchPrevented)) {
			if (supplied != null)
				supplied.close();
			return java.util.concurrent.CompletableFuture
				.completedFuture(new RunJobAdapter.Submission.Refused("RUN_STOP_REQUESTED"));
		}
		if (commands.submissionClaimed(run.runId())) {
			if (supplied != null)
				supplied.close();
			return jobs.submit(run.runId(), run.task(), null);
		}
		var prepared = supplied;
		try {
			if (prepared == null) {
				var service = broker.getIfAvailable();
				if (service == null)
					throw new IllegalStateException("Recorded launch credentials are unavailable");
				prepared = new LocalRunAdmission.Prepared(run.definition(), run.task(),
						service.restoreTraining(run.runId()));
			}
			if (run.task().runtimePullSecret() != null) {
				var helper = pulls.getIfAvailable();
				if (helper == null)
					throw new IllegalStateException("Runtime pull helper unavailable");
				if (!helper.installed(run)) {
					if (prepared.runtimePull() != null)
						helper.install(run, prepared.runtimePull());
					else {
						var service = broker.getIfAvailable();
						if (service == null)
							throw new IllegalStateException("Runtime pull broker unavailable");
						try (var restored = service.restoreRuntimePull(run.runId(),
								Path.of(System.getProperty("java.io.tmpdir")))) {
							helper.install(run, restored);
						}
					}
				}
			}
			var owned = prepared;
			return jobs.submit(run.runId(), run.task(), owned.credentials())
				.whenComplete((result, failure) -> owned.close());
		}
		catch (RuntimeException failure) {
			if (prepared != null)
				prepared.close();
			throw failure;
		}
	}

}
