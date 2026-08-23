package de.zorro909.skywright.backend.datasetpublication;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
final class LocalDatasetPublicationWorkerRecoveryScheduler implements DatasetPublicationWorkerRecoveryScheduler {

	private final ScheduledExecutorService executor = Executors
		.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("dataset-worker-recovery").factory());

	@Override
	public void retry(Runnable action, int attempt) {
		long delaySeconds = Math.min(1L << Math.min(attempt, 6), 60);
		this.executor.schedule(action, delaySeconds, TimeUnit.SECONDS);
	}

	@PreDestroy
	void close() {
		this.executor.shutdownNow();
	}

}
