package de.zorro909.skywright.backend.datasetpublication;

import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
final class DatasetPublicationWorkerDispatcher {

	private final DatasetPublicationService publications;

	private final DatasetPublicationWorkerLauncher launcher;

	private final DatasetPublicationWorkerRecovery recovery;

	private final ExecutorService executor = Executors
		.newSingleThreadExecutor(Thread.ofPlatform().name("dataset-transfer-worker-dispatcher").factory());

	private final Set<UUID> dispatched = ConcurrentHashMap.newKeySet();

	DatasetPublicationWorkerDispatcher(DatasetPublicationService publications,
			DatasetPublicationWorkerLauncher launcher, DatasetPublicationWorkerRecovery recovery) {
		this.publications = publications;
		this.launcher = launcher;
		this.recovery = recovery;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void requested(DatasetPublicationVerificationRequested event) {
		dispatch(event.publicationId());
	}

	@EventListener(ApplicationReadyEvent.class)
	void resume() {
		this.publications.pendingVerifications()
			.forEach(publicationId -> this.recovery.whenRecovered(publicationId, () -> dispatch(publicationId)));
	}

	private void dispatch(UUID publicationId) {
		if (this.dispatched.add(publicationId)) {
			this.executor.submit(() -> verify(publicationId));
		}
	}

	private void verify(UUID publicationId) {
		try {
			DatasetPublicationView publication = this.publications.verificationInput(publicationId);
			if (publication == null) {
				return;
			}
			DatasetPublicationWorkerResult result = this.launcher.verify(publication);
			if (result.verified()) {
				this.publications.commit(publicationId, result);
			}
			else {
				this.publications.fail(publicationId, result);
			}
		}
		catch (RuntimeException failure) {
			this.publications.fail(publicationId, new DatasetPublicationWorkerResult(false, java.util.List.of(), 0, 0,
					null, 0, "DATASET_VERIFICATION_UNAVAILABLE", true));
		}
		finally {
			this.dispatched.remove(publicationId);
		}
	}

	@PreDestroy
	void close() {
		this.executor.shutdownNow();
	}

}
