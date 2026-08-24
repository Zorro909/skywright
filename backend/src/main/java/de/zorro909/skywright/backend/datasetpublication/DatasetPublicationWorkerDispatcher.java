package de.zorro909.skywright.backend.datasetpublication;

import de.zorro909.skywright.backend.datasetcatalog.DatasetCatalogException;
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

	private final DatasetPublicationVerifier verifier;

	private final DatasetPublicationWorkerRecovery recovery;

	private final DatasetPublicationWorkerRecoveryScheduler scheduler;

	private final ExecutorService executor = Executors
		.newSingleThreadExecutor(Thread.ofPlatform().name("dataset-transfer-worker-dispatcher").factory());

	private final Set<UUID> dispatched = ConcurrentHashMap.newKeySet();

	DatasetPublicationWorkerDispatcher(DatasetPublicationService publications, DatasetPublicationVerifier verifier,
			DatasetPublicationWorkerRecovery recovery, DatasetPublicationWorkerRecoveryScheduler scheduler) {
		this.publications = publications;
		this.verifier = verifier;
		this.recovery = recovery;
		this.scheduler = scheduler;
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
			this.executor.submit(() -> verify(publicationId, 0));
		}
	}

	private void verify(UUID publicationId, int attempt) {
		boolean retryScheduled = false;
		try {
			DatasetPublicationView publication = this.publications.verificationInput(publicationId);
			if (publication == null) {
				return;
			}
			DatasetPublicationWorkerResult result = this.verifier.verify(publication);
			if (result.verified()) {
				this.publications.commit(publicationId, result);
			}
			else {
				this.publications.fail(publicationId, result);
			}
		}
		catch (DatasetPublicationException failure) {
			this.publications.fail(publicationId, new DatasetPublicationWorkerResult(false, java.util.List.of(), 0, 0,
					null, 0, failure.errorCode(), failure.retryable()));
		}
		catch (DatasetCatalogException failure) {
			this.publications.fail(publicationId, new DatasetPublicationWorkerResult(false, java.util.List.of(), 0, 0,
					null, 0, failure.errorCode(), false));
		}
		catch (RuntimeException failure) {
			try {
				this.publications.fail(publicationId, new DatasetPublicationWorkerResult(false, java.util.List.of(), 0,
						0, null, 0, "DATASET_VERIFICATION_UNAVAILABLE", true));
			}
			catch (RuntimeException persistenceFailure) {
				retryScheduled = true;
				this.scheduler.retry(() -> verify(publicationId, attempt + 1), attempt);
			}
		}
		finally {
			if (!retryScheduled) {
				this.dispatched.remove(publicationId);
			}
		}
	}

	@PreDestroy
	void close() {
		this.executor.shutdownNow();
	}

}
