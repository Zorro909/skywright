package de.zorro909.skywright.backend.runlog;

import de.zorro909.skywright.backend.runsubmission.RunAcceptanceStore;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "skywright.run-log-capture.enabled", matchIfMissing = true)
final class RunLogReconciler {

	private final RunAcceptanceStore runs;

	private final RunLogArchives archives;

	private final ThreadPoolExecutor executor;

	private UUID cursor;

	RunLogReconciler(RunAcceptanceStore runs, RunLogArchives archives,
			@Qualifier("runLogExecutor") ThreadPoolExecutor executor) {
		this.runs = runs;
		this.archives = archives;
		this.executor = executor;
	}

	@Scheduled(fixedDelayString = "PT5S", initialDelayString = "PT5S", scheduler = "runLogScheduler")
	public void reconcile() {
		var page = runs.page(cursor, 16);
		if (page.isEmpty()) {
			cursor = null;
			return;
		}
		for (UUID id : page) {
			try {
				executor.execute(() -> {
					try {
						archives.reconcile(id);
					}
					catch (RuntimeException unavailable) {
						/* Retry from durable cursor after the lease expires. */ }
				});
				cursor = id;
			}
			catch (java.util.concurrent.RejectedExecutionException full) {
				return;
			}
		}
	}

}
