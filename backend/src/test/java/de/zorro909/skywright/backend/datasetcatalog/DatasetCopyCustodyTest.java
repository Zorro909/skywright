package de.zorro909.skywright.backend.datasetcatalog;

import static org.assertj.core.api.Assertions.*;

import de.zorro909.skywright.backend.runstore.ResolvedTargetStorage;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;

class DatasetCopyCustodyTest {

	@Test
	void delayedProcessExitRetainsGlobalWorkerAdmissionAfterDispatcherReturns() throws Exception {
		Process child = new ProcessBuilder("sleep", "120").start();
		var facts = new Facts();
		var launcher = new DatasetCopyWorkerLauncher(id -> target(id), facts, Duration.ofSeconds(1),
				builder -> new DelayedExit(child));
		try {
			launcher.recover();
			assertThatThrownBy(() -> launcher.execute("verify", null, List.of(), copy(), null, 0))
				.hasMessageContaining("DATASET_COPY_WORKER_DEADLINE");
			assertThat(child.isAlive()).isTrue();
			assertThat(launcher.ready()).as("New work must wait for the existing child to leave custody").isFalse();
			assertThat(facts.released.getCount()).isEqualTo(1);
			child.destroyForcibly();
			assertThat(facts.released.await(5, TimeUnit.SECONDS)).isTrue();
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (!launcher.ready() && System.nanoTime() < deadline)
				Thread.sleep(10);
			assertThat(launcher.ready()).isTrue();
		}
		finally {
			child.destroyForcibly();
			launcher.close();
		}
	}

	@Test
	void failedPrelaunchReleaseClosesAdmissionUntilDurableRecoverySucceeds() {
		var facts = new Facts();
		facts.failPreparation = true;
		facts.failRelease = true;
		var launcher = new DatasetCopyWorkerLauncher(id -> target(id), facts, Duration.ofSeconds(1), builder -> {
			throw new AssertionError("Preparation failed before launch");
		});
		try {
			launcher.recover();
			assertThatThrownBy(() -> launcher.execute("verify", null, List.of(), copy(), null, 0))
				.hasMessageContaining("preparation unavailable");
			assertThat(launcher.ready()).isFalse();
			assertThat(facts.open()).hasSize(1);
			launcher.recover();
			assertThat(launcher.ready()).isTrue();
			assertThat(facts.open()).isEmpty();
			assertThat(java.nio.file.Files.exists(facts.directory)).isFalse();
		}
		finally {
			launcher.close();
		}
	}

	private static DatasetCopyView copy() {
		var now = Instant.now();
		var generation = new DatasetCopyGenerationView(1, "copy", "manifest", "fingerprint", 0, now, now, true,
				DatasetCopyAvailability.AVAILABLE);
		return new DatasetCopyView(UUID.randomUUID(), UUID.randomUUID(), DatasetCopyRole.REPLICA, 1, generation,
				List.of(generation), 0);
	}

	private static ResolvedTargetStorage target(UUID id) {
		return new ResolvedTargetStorage(id.toString(), URI.create("http://127.0.0.1:1"), "bucket", Region.US_EAST_1,
				true, Map.of(), StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")), "dataset",
				"maintenance", UUID.randomUUID(), 1);
	}

	private static final class Facts extends DatasetCopyWorkerProjections {

		private final UUID id = UUID.randomUUID();

		private final CountDownLatch released = new CountDownLatch(1);

		private boolean projected;

		private boolean failPreparation;

		private boolean failRelease;

		private Path directory;

		private Long pid;

		private Instant startedAt;

		Facts() {
			super(null);
		}

		@Override
		UUID projected(UUID copy, UUID binding, long revision) {
			this.projected = true;
			return this.id;
		}

		@Override
		void prepared(UUID id, Path directory) {
			this.directory = directory;
			if (this.failPreparation)
				throw new IllegalStateException("preparation unavailable");
		}

		@Override
		void launched(UUID id, long pid, Instant startedAt) {
			this.pid = pid;
			this.startedAt = startedAt;
		}

		@Override
		void released(UUID id) {
			if (this.failRelease) {
				this.failRelease = false;
				throw new IllegalStateException("database unavailable");
			}
			if (this.pid != null)
				assertThat(ProcessHandle.of(this.pid).filter(ProcessHandle::isAlive)).isEmpty();
			this.released.countDown();
		}

		@Override
		List<Open> open() {
			return this.projected && this.released.getCount() != 0 ? List.of(new Open(this.id, UUID.randomUUID(),
					this.pid, this.startedAt, this.directory == null ? null : this.directory.toString())) : List.of();
		}

	}

	/** Simulates a kernel that has accepted termination but has not reported exit yet. */
	private static final class DelayedExit extends Process {

		private final Process child;

		DelayedExit(Process child) {
			this.child = child;
		}

		@Override
		public OutputStream getOutputStream() {
			return OutputStream.nullOutputStream();
		}

		@Override
		public InputStream getInputStream() {
			return InputStream.nullInputStream();
		}

		@Override
		public InputStream getErrorStream() {
			return InputStream.nullInputStream();
		}

		@Override
		public int waitFor() throws InterruptedException {
			return this.child.waitFor();
		}

		@Override
		public boolean waitFor(long timeout, TimeUnit unit) {
			return false;
		}

		@Override
		public int exitValue() {
			return this.child.exitValue();
		}

		@Override
		public void destroy() {
		}

		@Override
		public Process destroyForcibly() {
			return this;
		}

		@Override
		public boolean isAlive() {
			return this.child.isAlive();
		}

		@Override
		public long pid() {
			return this.child.pid();
		}

		@Override
		public ProcessHandle.Info info() {
			return this.child.info();
		}

		@Override
		public CompletableFuture<Process> onExit() {
			return this.child.onExit().thenApply(ignored -> this);
		}

	}

}
