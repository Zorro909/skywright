package de.zorro909.skywright.backend.datasetpublication;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DatasetPublicationWorkerDispatcherTest {

	@Test
	void ambiguousDatabaseCommitResponseRetainsTheCommittedResult() throws Exception {
		UUID publicationId = UUID.randomUUID();
		var publications = new AmbiguousCommitService(publicationId);
		var scheduler = new RecordingScheduler();
		var dispatcher = new DatasetPublicationWorkerDispatcher(publications,
				publication -> new DatasetPublicationWorkerResult(true, List.of(), 1, 1, Instant.now(), 123, null,
						false),
				new DatasetPublicationWorkerRecovery(null, null, null), scheduler);
		try {
			dispatcher.requested(new DatasetPublicationVerificationRequested(publicationId));

			publications.failureReconciled.get(5, TimeUnit.SECONDS);
			assertThat(publications.committed).isTrue();
			assertThat(publications.commitAttempts).hasValue(1);
			assertThat(scheduler.attempts).isEmpty();
		}
		finally {
			dispatcher.close();
		}
	}

	@Test
	void recordsTransientManagedProjectionLossAgainstTheSameDurablePublication() throws Exception {
		UUID publicationId = UUID.randomUUID();
		var publications = new RecordingService(publicationId);
		var dispatcher = new DatasetPublicationWorkerDispatcher(publications,
				publication -> DatasetPublicationWorkerLauncher.projectionFailure(),
				new DatasetPublicationWorkerRecovery(null, null, null), new RecordingScheduler());
		try {
			dispatcher.requested(new DatasetPublicationVerificationRequested(publicationId));

			DatasetPublicationWorkerResult failure = publications.failure.get(5, TimeUnit.SECONDS);
			assertThat(publications.requestedPublicationId).isEqualTo(publicationId);
			assertThat(failure.failureCode()).isEqualTo("DATASET_PROJECTION_UNAVAILABLE");
			assertThat(failure.retryable()).isTrue();
			assertThat(DatasetPublicationService.failureDetail(failure))
				.isEqualTo("Managed credential projection is temporarily unavailable");
			assertThat(DatasetPublicationService.unavailableSource(failure)).isEqualTo("Managed Credential Projection");
		}
		finally {
			dispatcher.close();
		}
	}

	@Test
	void databaseOutageWhileRecordingFailureSchedulesRedispatchWithoutRestart() throws Exception {
		var publications = new DatabaseOutageService();
		var scheduler = new RecordingScheduler();
		var dispatcher = new DatasetPublicationWorkerDispatcher(publications, publication -> {
			throw new AssertionError("verification must not start while the database is unavailable");
		}, new DatasetPublicationWorkerRecovery(null, null, null), scheduler);
		try {
			dispatcher.requested(new DatasetPublicationVerificationRequested(UUID.randomUUID()));

			Runnable retry = scheduler.queued.get(5, TimeUnit.SECONDS);
			assertThat(publications.verificationInputAttempts).hasValue(1);
			assertThat(publications.failureRecordingAttempts).hasValue(1);

			retry.run();

			assertThat(publications.secondVerificationInput.get(5, TimeUnit.SECONDS)).isNull();
			assertThat(publications.verificationInputAttempts).hasValue(2);
		}
		finally {
			dispatcher.close();
		}
	}

	@Test
	void backendShutdownLeavesVerificationPendingForStartupRecovery() throws Exception {
		UUID publicationId = UUID.randomUUID();
		var publications = new RecordingService(publicationId);
		var verificationInterrupted = new CompletableFuture<Void>();
		var dispatcher = new DatasetPublicationWorkerDispatcher(publications, publication -> {
			verificationInterrupted.complete(null);
			return DatasetPublicationWorkerLauncher.interrupted();
		}, new DatasetPublicationWorkerRecovery(null, null, null), new RecordingScheduler());
		try {
			dispatcher.requested(new DatasetPublicationVerificationRequested(publicationId));

			verificationInterrupted.get(5, TimeUnit.SECONDS);
			assertThat(publications.failure).isNotDone();
		}
		finally {
			dispatcher.close();
		}
	}

	private abstract static class StubPublicationOperations implements DatasetPublicationOperations {

		@Override
		public List<UUID> pendingVerifications() {
			return List.of();
		}

		@Override
		public void commit(UUID publicationId, DatasetPublicationWorkerResult verified) {
			throw new AssertionError("commit was not expected");
		}

	}

	private static final class AmbiguousCommitService extends StubPublicationOperations {

		private final UUID publicationId;

		private final AtomicInteger commitAttempts = new AtomicInteger();

		private final CompletableFuture<Void> failureReconciled = new CompletableFuture<>();

		private volatile boolean committed;

		private AmbiguousCommitService(UUID publicationId) {
			this.publicationId = publicationId;
		}

		@Override
		public DatasetPublicationView verificationInput(UUID requestedPublicationId) {
			assertThat(requestedPublicationId).isEqualTo(this.publicationId);
			return publicationView(this.publicationId);
		}

		@Override
		public void commit(UUID committedPublicationId, DatasetPublicationWorkerResult verified) {
			assertThat(committedPublicationId).isEqualTo(this.publicationId);
			assertThat(verified.verified()).isTrue();
			this.commitAttempts.incrementAndGet();
			this.committed = true;
			throw new IllegalStateException("database response lost after commit");
		}

		@Override
		public void fail(UUID failedPublicationId, DatasetPublicationWorkerResult failure) {
			assertThat(failedPublicationId).isEqualTo(this.publicationId);
			assertThat(this.committed).isTrue();
			this.failureReconciled.complete(null);
		}

	}

	private static final class DatabaseOutageService extends StubPublicationOperations {

		private final AtomicInteger verificationInputAttempts = new AtomicInteger();

		private final AtomicInteger failureRecordingAttempts = new AtomicInteger();

		private final CompletableFuture<DatasetPublicationView> secondVerificationInput = new CompletableFuture<>();

		@Override
		public DatasetPublicationView verificationInput(UUID publicationId) {
			if (this.verificationInputAttempts.incrementAndGet() == 1) {
				throw new IllegalStateException("database unavailable");
			}
			this.secondVerificationInput.complete(null);
			return null;
		}

		@Override
		public void fail(UUID publicationId, DatasetPublicationWorkerResult failure) {
			this.failureRecordingAttempts.incrementAndGet();
			throw new IllegalStateException("database unavailable");
		}

	}

	private static final class RecordingService extends StubPublicationOperations {

		private final UUID publicationId;

		private final CompletableFuture<DatasetPublicationWorkerResult> failure = new CompletableFuture<>();

		private volatile UUID requestedPublicationId;

		private RecordingService(UUID publicationId) {
			this.publicationId = publicationId;
		}

		@Override
		public DatasetPublicationView verificationInput(UUID requestedPublicationId) {
			this.requestedPublicationId = requestedPublicationId;
			return publicationView(this.publicationId);
		}

		@Override
		public void fail(UUID failedPublicationId, DatasetPublicationWorkerResult result) {
			assertThat(failedPublicationId).isEqualTo(this.publicationId);
			this.failure.complete(result);
		}

	}

	private static DatasetPublicationView publicationView(UUID publicationId) {
		Instant now = Instant.now();
		return new DatasetPublicationView(publicationId, DatasetPublicationState.VERIFYING, UUID.randomUUID(),
				UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0,
				DatasetPublicationPreferredDecision.ADVANCE_PREFERRED, "v1", "mosaicml-streaming-mds@2",
				"sha256:" + "1".repeat(64), "sha256:" + "2".repeat(64), 1, 1, "datasets/payload",
				"operations/publication", 1, 1, 0, 0, null, false, true, null, null, null, null, now, now, null, null,
				0);
	}

	private static final class RecordingScheduler implements DatasetPublicationWorkerRecoveryScheduler {

		private final CompletableFuture<Runnable> queued = new CompletableFuture<>();

		private final List<Integer> attempts = new ArrayList<>();

		@Override
		public void retry(Runnable action, int attempt) {
			this.attempts.add(attempt);
			this.queued.complete(action);
		}

	}

}
