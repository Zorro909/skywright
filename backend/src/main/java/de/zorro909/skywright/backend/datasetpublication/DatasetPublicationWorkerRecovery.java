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

	private final Map<UUID, CompletableFuture<Void>> recoveringPublications = new ConcurrentHashMap<>();

	DatasetPublicationWorkerRecovery(DatasetPublicationCredentialProjectionLifecycle projections,
			DatasetPublicationWorkerProcessMonitor processes) {
		this.projections = projections;
		this.processes = processes;
	}

	@EventListener(ApplicationReadyEvent.class)
	@Order(Ordered.HIGHEST_PRECEDENCE)
	void resume() {
		this.projections.open().forEach(this::resume);
	}

	private void resume(DatasetPublicationOpenCredentialProjection projection) {
		this.processes.completion(projection)
			.ifPresentOrElse(completion -> this.recoveringPublications.put(projection.publicationId(),
					completion.thenRun(() -> complete(projection))), () -> complete(projection));
	}

	void whenRecovered(UUID publicationId, Runnable action) {
		this.recoveringPublications.getOrDefault(publicationId, CompletableFuture.completedFuture(null))
			.whenComplete((ignored, failure) -> action.run());
	}

	private void complete(DatasetPublicationOpenCredentialProjection projection) {
		this.projections.released(projection.projectionId());
		DatasetPublicationWorkerLauncher.deleteJobFiles(projection.jobDirectory());
	}

}
