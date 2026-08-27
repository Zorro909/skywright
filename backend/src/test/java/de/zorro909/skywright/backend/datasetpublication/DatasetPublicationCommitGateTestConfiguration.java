package de.zorro909.skywright.backend.datasetpublication;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration(proxyBeanMethods = false)
public class DatasetPublicationCommitGateTestConfiguration {

	private static final AtomicReference<CyclicBarrier> NEXT_COMMITS = new AtomicReference<>();

	private static final AtomicBoolean FAIL_NEXT_CLEANUP = new AtomicBoolean();

	private static final AtomicReference<CommitBlock> NEXT_COMMIT = new AtomicReference<>();

	public static void blockNextCommits(int parties) {
		NEXT_COMMITS.set(new CyclicBarrier(parties));
	}

	public static void failNextCleanup() {
		FAIL_NEXT_CLEANUP.set(true);
	}

	public static void blockNextCommit() {
		NEXT_COMMIT.set(new CommitBlock(new CountDownLatch(1), new CountDownLatch(1)));
	}

	public static void awaitNextCommitStarted() throws InterruptedException, TimeoutException {
		CommitBlock block = NEXT_COMMIT.get();
		if (block == null || !block.entered().await(30, TimeUnit.SECONDS)) {
			throw new TimeoutException("Dataset Publication commit did not reach its boundary");
		}
	}

	public static void releaseNextCommit() {
		CommitBlock block = NEXT_COMMIT.get();
		if (block != null) {
			block.release().countDown();
		}
	}

	@Bean
	@Primary
	DatasetPublicationCleanupGate integrationDatasetPublicationCleanupGate() {
		return (publication, operationOnly) -> {
			if (FAIL_NEXT_CLEANUP.compareAndSet(true, false)) {
				throw new IllegalStateException("Injected cleanup failure");
			}
		};
	}

	@Bean
	@Primary
	DatasetPublicationCommitGate integrationDatasetPublicationCommitGate() {
		return datasetId -> {
			CommitBlock block = NEXT_COMMIT.get();
			if (block != null) {
				block.entered().countDown();
				try {
					if (!block.release().await(30, TimeUnit.SECONDS)) {
						throw new IllegalStateException("Timed out while blocking Dataset Publication commit");
					}
				}
				catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException("Interrupted while blocking Dataset Publication commit", exception);
				}
				finally {
					NEXT_COMMIT.compareAndSet(block, null);
				}
			}
			CyclicBarrier barrier = NEXT_COMMITS.get();
			if (barrier == null) {
				return;
			}
			try {
				barrier.await(30, TimeUnit.SECONDS);
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while synchronizing Dataset Publication commits",
						exception);
			}
			catch (BrokenBarrierException | TimeoutException exception) {
				throw new IllegalStateException("Could not synchronize Dataset Publication commits", exception);
			}
			finally {
				NEXT_COMMITS.compareAndSet(barrier, null);
			}
		};
	}

	private record CommitBlock(CountDownLatch entered, CountDownLatch release) {
	}

}
