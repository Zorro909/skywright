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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DatasetPublicationWorkerDispatcherTest {

	@ParameterizedTest
	@ValueSource(strings = { "DATASET_CORRUPT_EVIDENCE", "DATASET_PROJECTION_UNAVAILABLE" })
	void recordsVerifierFaultsAgainstTheSameDurablePublication(String failureCode) throws Exception {
		UUID publicationId = UUID.randomUUID();
		var publications = new RecordingService(publicationId);
		var dispatcher = new DatasetPublicationWorkerDispatcher(publications,
				publication -> new DatasetPublicationWorkerResult(false, List.of(), 0, 0, null, 0, failureCode, true),
				new DatasetPublicationWorkerRecovery(null, null, null), new RecordingScheduler());
		try {
			dispatcher.requested(new DatasetPublicationVerificationRequested(publicationId));

			DatasetPublicationWorkerResult failure = publications.failure.get(5, TimeUnit.SECONDS);
			assertThat(publications.requestedPublicationId).isEqualTo(publicationId);
			assertThat(failure.failureCode()).isEqualTo(failureCode);
			assertThat(failure.retryable()).isTrue();
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

	private static final class DatabaseOutageService extends DatasetPublicationService {

		private final AtomicInteger verificationInputAttempts = new AtomicInteger();

		private final AtomicInteger failureRecordingAttempts = new AtomicInteger();

		private final CompletableFuture<DatasetPublicationView> secondVerificationInput = new CompletableFuture<>();

		private DatabaseOutageService() {
			super(null, null, null, null, null);
		}

		@Override
		DatasetPublicationView verificationInput(UUID publicationId) {
			if (this.verificationInputAttempts.incrementAndGet() == 1) {
				throw new IllegalStateException("database unavailable");
			}
			this.secondVerificationInput.complete(null);
			return null;
		}

		@Override
		void fail(UUID publicationId, DatasetPublicationWorkerResult failure) {
			this.failureRecordingAttempts.incrementAndGet();
			throw new IllegalStateException("database unavailable");
		}

	}

	private static final class RecordingService extends DatasetPublicationService {

		private final UUID publicationId;

		private final CompletableFuture<DatasetPublicationWorkerResult> failure = new CompletableFuture<>();

		private volatile UUID requestedPublicationId;

		private RecordingService(UUID publicationId) {
			super(null, null, null, null, null);
			this.publicationId = publicationId;
		}

		@Override
		DatasetPublicationView verificationInput(UUID requestedPublicationId) {
			this.requestedPublicationId = requestedPublicationId;
			Instant now = Instant.now();
			return new DatasetPublicationView(this.publicationId, DatasetPublicationState.VERIFYING, UUID.randomUUID(),
					UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0,
					DatasetPublicationPreferredDecision.ADVANCE_PREFERRED, "v1", "mosaicml-streaming-mds@2",
					"sha256:" + "1".repeat(64), "sha256:" + "2".repeat(64), 1, 1, "datasets/payload",
					"operations/publication", 1, 1, 0, 0, null, false, true, null, null, null, null, now, now, null,
					null, 0);
		}

		@Override
		void fail(UUID failedPublicationId, DatasetPublicationWorkerResult result) {
			assertThat(failedPublicationId).isEqualTo(this.publicationId);
			this.failure.complete(result);
		}

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
