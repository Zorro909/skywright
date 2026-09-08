package de.zorro909.skywright.backend.datasetcatalog;

import static org.assertj.core.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DatasetPromotionTest {

	private final DatasetCatalog catalog = new DatasetCatalog(new InMemoryDatasetCatalogRepository(),
			Clock.systemUTC());

	private final UUID definition = UUID.randomUUID();

	private final UUID authority = UUID.randomUUID();

	private final UUID replica = UUID.randomUUID();

	private DatasetCopyOperationView promotion() {
		var now = Instant.now();
		this.catalog.publish(new DatasetPublication(UUID.randomUUID(), this.definition, "v1", "format",
				"sha256:content", "sha256:manifest", this.authority, UUID.randomUUID(), "source", 1, now,
				List.of(new DatasetManifestEntry("shard", 1, "checksum"))));
		this.catalog.addReplica(this.definition,
				new DatasetReplicaPublication(this.replica, UUID.randomUUID(), "replica", 1, now), 1);
		return this.catalog.promote(this.definition, this.replica, 2);
	}

	@Test
	void concurrentLeaseRequiresFreshVerificationAndCannotBeLostDuringPromotion() {
		var operation = promotion();
		this.catalog.acquireLease(this.definition, this.replica, 1, 3, UUID.randomUUID());
		assertThatThrownBy(() -> this.catalog.completePromotion(this.definition, operation.id(), 3))
			.isInstanceOf(DatasetCatalogConflictException.class);
		assertAuthority(this.authority);
		this.catalog.completePromotion(this.definition, operation.id(), 4);
		assertAuthority(this.replica);
		assertThat(this.catalog.get(this.definition).copies()).filteredOn(copy -> copy.id().equals(this.replica))
			.singleElement()
			.extracting(DatasetCopyView::activeLeaseCount)
			.isEqualTo(1L);
	}

	@Test
	void cancelledAndSupersededProofCannotPromoteOrRestoreDeprecatedAdmission() {
		var operation = promotion();
		this.catalog.deprecateGeneration(this.definition, this.replica, 1, 3);
		this.catalog.cancelOperation(this.definition, operation.id(), 4);
		assertThatThrownBy(() -> this.catalog.completePromotion(this.definition, operation.id(), 5))
			.isInstanceOf(DatasetCatalogConflictException.class);
		assertAuthority(this.authority);
		assertThat(this.catalog.get(this.definition).copies()).filteredOn(copy -> copy.id().equals(this.replica))
			.singleElement()
			.satisfies(copy -> assertThat(copy.currentGeneration().acceptingLeases()).isFalse());
	}

	@Test
	void anotherPromotionCannotStartUntilCurrentPromotionEnds() {
		var operation = promotion();
		UUID other = UUID.randomUUID();
		this.catalog.addReplica(this.definition,
				new DatasetReplicaPublication(other, UUID.randomUUID(), "other", 1, Instant.now()), 3);
		assertThatThrownBy(() -> this.catalog.promote(this.definition, other, 4))
			.hasMessageContaining("DATASET_PROMOTION_ACTIVE");
		this.catalog.failOperation(this.definition, operation.id(), "DATASET_COPY_MANIFEST_MISMATCH", "Mismatch", true,
				4);
		this.catalog.promote(this.definition, other, 5);
		assertThatThrownBy(() -> this.catalog.retryOperation(this.definition, operation.id(), 6))
			.hasMessageContaining("DATASET_PROMOTION_ACTIVE");
		assertAuthority(this.authority);
	}

	@Test
	void blockedVerificationDoesNotOccupySchedulerAndCancellationInterruptsWorker() throws Exception {
		var operation = promotion();
		var entered = new CountDownLatch(1);
		var interrupted = new CountDownLatch(1);
		var calls = new java.util.concurrent.atomic.AtomicInteger();
		var storage = new DatasetCopyStorage() {
			@Override
			public void verify(DatasetDefinitionView definition, List<DatasetManifestEntry> manifest,
					DatasetCopyView copy) {
				calls.incrementAndGet();
				entered.countDown();
				try {
					new CountDownLatch(1).await();
				}
				catch (InterruptedException cancelled) {
					interrupted.countDown();
					throw new IllegalStateException("cancelled");
				}
			}

			@Override
			public VerifiedDatasetReplacement stageReplacement(DatasetDefinitionView definition,
					List<DatasetManifestEntry> manifest, DatasetCopyView copy, UUID operationId) {
				throw new AssertionError("Unexpected refresh");
			}

			@Override
			public VerifiedDatasetReplacement verifyReplacement(DatasetDefinitionView definition,
					List<DatasetManifestEntry> manifest, DatasetCopyView copy, UUID operationId) {
				throw new AssertionError("Unexpected refresh");
			}

			@Override
			public void deleteAndVerify(List<DatasetManifestEntry> manifest, DatasetCopyView copy, long generation) {
				throw new AssertionError("Unexpected delete");
			}
		};
		var worker = new DatasetCopyMaintenanceWorker(this.catalog, storage);
		try {
			worker.resumeDurableOperations();
			assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
			org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> {
				for (int count = 0; count < 10; count++)
					worker.resumeDurableOperations();
				this.catalog.cancelOperation(this.definition, operation.id(), 3);
				worker.resumeDurableOperations();
			});
			assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(calls.get()).isEqualTo(1);
			assertAuthority(this.authority);
		}
		finally {
			worker.close();
		}
	}

	private void assertAuthority(UUID expected) {
		assertThat(this.catalog.get(this.definition).copies())
			.filteredOn(copy -> copy.role() == DatasetCopyRole.AUTHORITY)
			.singleElement()
			.extracting(DatasetCopyView::id)
			.isEqualTo(expected);
	}

}
