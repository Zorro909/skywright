package de.zorro909.skywright.backend.datasetpublication;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration(proxyBeanMethods = false)
public class DatasetPublicationCommitGateTestConfiguration {

	private static final AtomicReference<CyclicBarrier> NEXT_COMMITS = new AtomicReference<>();

	public static void blockNextCommits(int parties) {
		NEXT_COMMITS.set(new CyclicBarrier(parties));
	}

	@Bean
	@Primary
	DatasetPublicationCommitGate integrationDatasetPublicationCommitGate() {
		return datasetId -> {
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

}
