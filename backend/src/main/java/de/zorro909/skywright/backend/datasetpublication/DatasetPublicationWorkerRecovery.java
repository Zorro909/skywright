package de.zorro909.skywright.backend.datasetpublication;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
final class DatasetPublicationWorkerRecovery {

	private final DatasetPublicationCredentialProjectionLifecycle projections;

	private final DatasetPublicationWorkerProcessMonitor processes;

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
			.ifPresentOrElse(completion -> completion.thenRun(() -> complete(projection)), () -> complete(projection));
	}

	private void complete(DatasetPublicationOpenCredentialProjection projection) {
		this.projections.released(projection.projectionId());
		DatasetPublicationWorkerLauncher.deleteJobFiles(projection.jobDirectory());
	}

}
