package de.zorro909.skywright.backend.datasetpublication;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.springframework.stereotype.Component;

@Component
final class LocalDatasetPublicationWorkerProcessMonitor implements DatasetPublicationWorkerProcessMonitor {

	@Override
	public Optional<CompletableFuture<Void>> completion(DatasetPublicationOpenCredentialProjection projection) {
		Optional<ProcessHandle> process = byPersistedPid(projection);
		return process.map(handle -> handle.onExit().thenApply(ignored -> null));
	}

	private static Optional<ProcessHandle> byPersistedPid(DatasetPublicationOpenCredentialProjection projection) {
		if (projection.workerPid() == null || projection.workerStartedAt() == null) {
			return Optional.empty();
		}
		return ProcessHandle.of(projection.workerPid())
			.filter(ProcessHandle::isAlive)
			.filter(handle -> handle.info().startInstant().filter(projection.workerStartedAt()::equals).isPresent());
	}

}
