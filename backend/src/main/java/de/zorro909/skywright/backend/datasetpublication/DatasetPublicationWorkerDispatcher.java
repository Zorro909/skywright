package de.zorro909.skywright.backend.datasetpublication;

import de.zorro909.skywright.backend.datasetcatalog.DatasetCatalogException;
import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
final class DatasetPublicationWorkerDispatcher {

	private final DatasetPublicationOperations publications;

	private final DatasetPublicationVerifier verifier;

	private final DatasetPublicationWorkerRecovery recovery;

	private final DatasetPublicationWorkerRecoveryScheduler scheduler;

	private final ExecutorService executor = Executors
		.newSingleThreadExecutor(Thread.ofPlatform().name("dataset-transfer-worker-dispatcher").factory());

	private final Set<UUID> dispatchedVerifications = ConcurrentHashMap.newKeySet();

	private final Set<UUID> dispatchedCleanups = ConcurrentHashMap.newKeySet();

	private final AtomicBoolean closing = new AtomicBoolean();

	DatasetPublicationWorkerDispatcher(DatasetPublicationOperations publications, DatasetPublicationVerifier verifier,
			DatasetPublicationWorkerRecovery recovery, DatasetPublicationWorkerRecoveryScheduler scheduler) {
		this.publications = publications;
		this.verifier = verifier;
		this.recovery = recovery;
		this.scheduler = scheduler;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void requested(DatasetPublicationVerificationRequested event) {
		dispatchVerification(event.publicationId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void requested(DatasetPublicationCleanupRequested event) {
		dispatchCleanup(event.publicationId());
	}

	@EventListener(ApplicationReadyEvent.class)
	void resume() {
		this.publications.pendingVerifications()
			.forEach(publicationId -> this.recovery.whenRecovered(publicationId,
					() -> dispatchVerification(publicationId)));
		this.publications.pendingCleanups()
			.forEach(publicationId -> this.recovery.whenRecovered(publicationId, () -> dispatchCleanup(publicationId)));
	}

	private void dispatchVerification(UUID publicationId) {
		if (this.dispatchedVerifications.add(publicationId)) {
			this.executor.submit(() -> verify(publicationId, 0));
		}
	}

	private void dispatchCleanup(UUID publicationId) {
		if (this.dispatchedCleanups.add(publicationId)) {
			this.executor.submit(() -> cleanup(publicationId, 0));
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
			if (this.closing.get() || "DATASET_VERIFICATION_INTERRUPTED".equals(result.failureCode())) {
				return;
			}
			if (result.verified()) {
				this.publications.commit(publicationId, result);
			}
			else {
				this.publications.fail(publicationId, result);
			}
		}
		catch (DatasetPublicationException failure) {
			retryScheduled = recordFailure(publicationId, new DatasetPublicationWorkerResult(false, java.util.List.of(),
					0, 0, null, 0, failure.errorCode(), failure.retryable()), attempt);
		}
		catch (DatasetCatalogException failure) {
			retryScheduled = recordFailure(publicationId, new DatasetPublicationWorkerResult(false, java.util.List.of(),
					0, 0, null, 0, failure.errorCode(), false), attempt);
		}
		catch (RuntimeException failure) {
			if (this.closing.get()) {
				return;
			}
			retryScheduled = recordFailure(publicationId, new DatasetPublicationWorkerResult(false, java.util.List.of(),
					0, 0, null, 0, "DATASET_DATABASE_UNAVAILABLE", true), attempt);
		}
		finally {
			if (!retryScheduled) {
				this.dispatchedVerifications.remove(publicationId);
			}
		}
	}

	private void cleanup(UUID publicationId, int attempt) {
		boolean retryScheduled = false;
		try {
			DatasetPublicationView publication = this.publications.cleanupInput(publicationId);
			if (publication == null) {
				return;
			}
			boolean operationOnly = publication.state() == DatasetPublicationState.PUBLISHED_CLEANUP_PENDING;
			DatasetPublicationWorkerResult result = this.verifier.cleanup(publication, operationOnly);
			if (this.closing.get() || "DATASET_VERIFICATION_INTERRUPTED".equals(result.failureCode())) {
				return;
			}
			if (result.verified()) {
				this.publications.cleanupSucceeded(publicationId, result);
			}
			else {
				this.publications.cleanupFailed(publicationId, result);
			}
		}
		catch (RuntimeException failure) {
			retryScheduled = recordCleanupFailure(publicationId, new DatasetPublicationWorkerResult(false,
					java.util.List.of(), 0, 0, null, 0, "DATASET_DATABASE_UNAVAILABLE", true), attempt);
		}
		finally {
			if (!retryScheduled) {
				this.dispatchedCleanups.remove(publicationId);
			}
		}
	}

	private boolean recordFailure(UUID publicationId, DatasetPublicationWorkerResult failure, int attempt) {
		try {
			this.publications.fail(publicationId, failure);
			return false;
		}
		catch (RuntimeException persistenceFailure) {
			this.scheduler.retry(() -> verify(publicationId, attempt + 1), attempt);
			return true;
		}
	}

	private boolean recordCleanupFailure(UUID publicationId, DatasetPublicationWorkerResult failure, int attempt) {
		try {
			this.publications.cleanupFailed(publicationId, failure);
			return false;
		}
		catch (RuntimeException persistenceFailure) {
			this.scheduler.retry(() -> cleanup(publicationId, attempt + 1), attempt);
			return true;
		}
	}

	@PreDestroy
	void close() {
		this.closing.set(true);
		this.executor.shutdownNow();
		try {
			this.executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}

}
