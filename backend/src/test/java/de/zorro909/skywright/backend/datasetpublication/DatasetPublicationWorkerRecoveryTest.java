package de.zorro909.skywright.backend.datasetpublication;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatasetPublicationWorkerRecoveryTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void restartReleasesProjectionAndJobFilesWhenSurvivingWorkerExits() throws Exception {
		UUID projectionId = UUID.randomUUID();
		UUID publicationId = UUID.randomUUID();
		Path jobDirectory = Files.createDirectory(this.temporaryDirectory.resolve("job"));
		Files.writeString(jobDirectory.resolve("job.json"), "{}");
		var open = new DatasetPublicationOpenCredentialProjection(projectionId, publicationId, 123L, null,
				jobDirectory);
		var projections = new RecordingProjectionLifecycle(open);
		var exited = new CompletableFuture<Void>();
		DatasetPublicationWorkerProcessMonitor processes = ignored -> Optional.of(exited);
		var recovery = new DatasetPublicationWorkerRecovery(projections, processes, new RecordingScheduler());

		recovery.resume();

		assertThat(projections.released).isEmpty();
		assertThat(jobDirectory).exists();

		exited.complete(null);

		assertThat(projections.released).containsExactly(projectionId);
		assertThat(jobDirectory).doesNotExist();
	}

	@Test
	void restartClosesProjectionWhenItsWorkerAlreadyExited() throws Exception {
		UUID projectionId = UUID.randomUUID();
		UUID publicationId = UUID.randomUUID();
		Path jobDirectory = Files.createDirectory(this.temporaryDirectory.resolve("completed-job"));
		var open = new DatasetPublicationOpenCredentialProjection(projectionId, publicationId, 456L, null,
				jobDirectory);
		var projections = new RecordingProjectionLifecycle(open);
		DatasetPublicationWorkerProcessMonitor processes = ignored -> Optional.empty();
		var recovery = new DatasetPublicationWorkerRecovery(projections, processes, new RecordingScheduler());

		recovery.resume();

		assertThat(projections.released).containsExactly(projectionId);
		assertThat(jobDirectory).doesNotExist();
	}

	@Test
	void localMonitorFindsTheExactSurvivingWorkerByPersistedIdentity() throws Exception {
		UUID projectionId = UUID.randomUUID();
		String executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
		Process worker = new ProcessBuilder(executable, "-cp", classPath,
				DatasetPublicationWorkerMarkerMain.class.getName(), projectionId.toString())
			.start();
		try {
			Thread.sleep(100);
			assertThat(worker.isAlive()).as("marker process is running").isTrue();
			var projection = new DatasetPublicationOpenCredentialProjection(projectionId, UUID.randomUUID(),
					worker.pid(), worker.toHandle().info().startInstant().orElseThrow(), null);
			var monitor = new LocalDatasetPublicationWorkerProcessMonitor();
			Optional<CompletableFuture<Void>> completion = Optional.empty();
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
			while (completion.isEmpty() && System.nanoTime() < deadline) {
				completion = monitor.completion(projection);
				if (completion.isEmpty()) {
					Thread.sleep(10);
				}
			}

			assertThat(completion).isPresent();
			worker.destroyForcibly();
			completion.orElseThrow().get(5, TimeUnit.SECONDS);
		}
		finally {
			worker.destroyForcibly();
		}
	}

	@Test
	void localMonitorRejectsPidWithoutStartIdentity() {
		var projection = new DatasetPublicationOpenCredentialProjection(UUID.randomUUID(), UUID.randomUUID(),
				ProcessHandle.current().pid(), null, null);

		assertThat(new LocalDatasetPublicationWorkerProcessMonitor().completion(projection)).isEmpty();
	}

	@Test
	void localMonitorRejectsMismatchedStartIdentity() {
		var projection = new DatasetPublicationOpenCredentialProjection(UUID.randomUUID(), UUID.randomUUID(),
				ProcessHandle.current().pid(), Instant.EPOCH, null);

		assertThat(new LocalDatasetPublicationWorkerProcessMonitor().completion(projection)).isEmpty();
	}

	@Test
	void restartDefersRedispatchUntilTheSurvivingWorkerIsReaped() {
		UUID publicationId = UUID.randomUUID();
		var open = new DatasetPublicationOpenCredentialProjection(UUID.randomUUID(), publicationId, 123L, Instant.EPOCH,
				null);
		var projections = new RecordingProjectionLifecycle(open);
		var exited = new CompletableFuture<Void>();
		var recovery = new DatasetPublicationWorkerRecovery(projections, ignored -> Optional.of(exited),
				new RecordingScheduler());
		var redispatched = new CompletableFuture<Void>();

		recovery.resume();
		recovery.whenRecovered(publicationId, () -> redispatched.complete(null));

		assertThat(redispatched).isNotDone();
		exited.complete(null);
		assertThat(redispatched).isCompleted();
		assertThat(projections.released).containsExactly(open.projectionId());
	}

	@Test
	void restartRetriesProjectionReleaseBeforeRedispatch() {
		UUID publicationId = UUID.randomUUID();
		var open = new DatasetPublicationOpenCredentialProjection(UUID.randomUUID(), publicationId, 123L, Instant.EPOCH,
				null);
		var projections = new RecordingProjectionLifecycle(open);
		projections.releaseFailure = new IllegalStateException("database unavailable");
		var exited = new CompletableFuture<Void>();
		var scheduler = new RecordingScheduler();
		var recovery = new DatasetPublicationWorkerRecovery(projections, ignored -> Optional.of(exited), scheduler);
		var redispatches = new AtomicInteger();

		recovery.resume();
		recovery.whenRecovered(publicationId, redispatches::incrementAndGet);
		exited.complete(null);

		assertThat(redispatches).hasValue(0);
		assertThat(projections.released).isEmpty();
		assertThat(scheduler.retries).hasSize(1);

		projections.releaseFailure = null;
		scheduler.runNext();

		assertThat(redispatches).hasValue(1);
		assertThat(projections.released).containsExactly(open.projectionId());
	}

	private static final class RecordingScheduler implements DatasetPublicationWorkerRecoveryScheduler {

		private final List<Runnable> retries = new ArrayList<>();

		@Override
		public void retry(Runnable action, int attempt) {
			this.retries.add(action);
		}

		private void runNext() {
			this.retries.removeFirst().run();
		}

	}

	private static final class RecordingProjectionLifecycle implements DatasetPublicationCredentialProjectionLifecycle {

		private final List<DatasetPublicationOpenCredentialProjection> open;

		private final List<UUID> released = new ArrayList<>();

		private RuntimeException releaseFailure;

		private RecordingProjectionLifecycle(DatasetPublicationOpenCredentialProjection open) {
			this.open = List.of(open);
		}

		@Override
		public UUID projected(UUID publicationId, UUID bindingId, long bindingRevision) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void prepared(UUID projectionId, Path jobDirectory) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void launched(UUID projectionId, long workerPid, java.time.Instant workerStartedAt) {
			throw new UnsupportedOperationException();
		}

		@Override
		public List<DatasetPublicationOpenCredentialProjection> open() {
			return this.open;
		}

		@Override
		public void released(UUID projectionId) {
			if (this.releaseFailure != null) {
				throw this.releaseFailure;
			}
			this.released.add(projectionId);
		}

	}

}
