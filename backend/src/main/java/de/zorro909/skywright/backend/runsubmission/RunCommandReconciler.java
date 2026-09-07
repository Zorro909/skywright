package de.zorro909.skywright.backend.runsubmission;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "skywright.run-commands.enabled", matchIfMissing = true)
final class RunCommandReconciler {

	private final RunCommandStore commands;

	private final RunCommandDelivery delivery;

	RunCommandReconciler(RunCommandStore commands, RunCommandDelivery delivery) {
		this.commands = commands;
		this.delivery = delivery;
	}

	@Scheduled(fixedDelayString = "PT2S", initialDelayString = "PT2S", scheduler = "runCommandScheduler")
	public void reconcile() {
		for (var id : commands.due(16)) {
			try {
				delivery.enqueue(id);
			}
			catch (java.util.concurrent.RejectedExecutionException full) {
				return;
			}
		}
	}

}
