package de.zorro909.skywright.backend.datasetpublication;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
final class DatasetPublicationWorkerRecovery {

	private final DatasetPublicationCredentialProjectionLifecycle projections;

	private final DatasetPublicationWorkerProcessMonitor processes;

	private final DatasetPublicationWorkerRecoveryScheduler scheduler;

	private final Map<UUID, CompletableFuture<Void>> recoveringPublications = new ConcurrentHashMap<>();

	DatasetPublicationWorkerRecovery(DatasetPublicationCredentialProjectionLifecycle projections,
			DatasetPublicationWorkerProcessMonitor processes, DatasetPublicationWorkerRecoveryScheduler scheduler) {
		this.projections = projections;
		this.processes = processes;
		this.scheduler = scheduler;
	}

	@EventListener(ApplicationReadyEvent.class)
	@Order(Ordered.HIGHEST_PRECEDENCE)
	void resume() {
		this.projections.open().forEach(this::resume);
	}

	private void resume(DatasetPublicationOpenCredentialProjection projection) {
		var recovered = new CompletableFuture<Void>();
		this.recoveringPublications.put(projection.publicationId(), recovered);
		this.processes.completion(projection)
			.ifPresentOrElse(completion -> completion.thenRun(() -> complete(projection, recovered, 0)),
					() -> complete(projection, recovered, 0));
	}

	void whenRecovered(UUID publicationId, Runnable action) {
		this.recoveringPublications.getOrDefault(publicationId, CompletableFuture.completedFuture(null))
			.thenRun(action);
	}

	private void complete(DatasetPublicationOpenCredentialProjection projection, CompletableFuture<Void> recovered,
			int attempt) {
		try {
			this.projections.released(projection.projectionId());
			DatasetPublicationWorkerLauncher.deleteJobFiles(projection.jobDirectory());
			recovered.complete(null);
		}
		catch (RuntimeException failure) {
			this.scheduler.retry(() -> complete(projection, recovered, attempt + 1), attempt);
		}
	}

}
