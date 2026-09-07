package de.zorro909.skywright.backend.runlifecycle;

import de.zorro909.skywright.backend.orchestration.RunJobAdapter;
import de.zorro909.skywright.backend.runsubmission.RunAcceptanceStore;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Retention extension, rebuilt from raw durable facts on every daily sweep. */
@Component
@ConditionalOnProperty(name = "skywright.run-retention.enabled", matchIfMissing = true)
public final class RunRetentionReconciler {

	private final RunAcceptanceStore runs;

	private final RunJobAdapter jobs;

	public RunRetentionReconciler(RunAcceptanceStore runs, RunJobAdapter jobs) {
		this.runs = runs;
		this.jobs = jobs;
	}

	@Scheduled(fixedDelayString = "PT24H", initialDelayString = "PT1M", scheduler = "runRetentionScheduler")
	public void sweep() {
		UUID cursor = null;
		while (!Thread.currentThread().isInterrupted()) {
			var page = runs.page(cursor, 100);
			if (page.isEmpty())
				return;
			for (var runId : page) {
				try {
					boolean terminal = runs.dispatchPrevented(runId)
							|| new RunLifecycleDerivation().terminalRetention(runs.retainedFacts(runId));
					if (!terminal)
						jobs.reconcile(runId).toCompletableFuture().get(5, TimeUnit.SECONDS);
				}
				catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					return;
				}
				catch (Exception unavailable) {
					/* The next sweep rebuilds from durable evidence. */ }
			}
			cursor = page.getLast();
		}
	}

}
