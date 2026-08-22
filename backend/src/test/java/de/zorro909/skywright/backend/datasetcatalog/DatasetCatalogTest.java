package de.zorro909.skywright.backend.datasetcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.Test;

final class DatasetCatalogTest {

	private static final Instant NOW = Instant.parse("2026-08-22T10:00:00Z");

	private final InMemoryDatasetCatalogRepository repository = new InMemoryDatasetCatalogRepository();

	private final DatasetCatalog catalog = new DatasetCatalog(this.repository, Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void publicationInstallsOneDefinitionAndAuthorityAtomicallyAndRetriesIdempotently() {
		DatasetPublication publication = publication();

		DatasetCatalogView first = this.catalog.publish(publication);
		DatasetCatalogView retry = this.catalog.publish(publication);

		assertThat(retry).isEqualTo(first);
		assertThat(first.revision()).isEqualTo(1);
		assertThat(first.definition().datasetId()).isEqualTo(publication.datasetId());
		assertThat(first.definition().definitionId()).isEqualTo(publication.definitionId());
		assertThat(first.copies()).singleElement().satisfies(copy -> {
			assertThat(copy.role()).isEqualTo(DatasetCopyRole.AUTHORITY);
			assertThat(copy.currentGeneration().number()).isEqualTo(1);
			assertThat(copy.currentGeneration().location()).isEqualTo(publication.location());
			assertThat(copy.currentGeneration().verifiedBytes()).isEqualTo(42);
		});
	}

	@Test
	void publicationRequiresACompleteIntegrityManifest() {
		DatasetPublication publication = publication();

		assertThatThrownBy(() -> this.catalog.publish(new DatasetPublication(publication.datasetId(),
				publication.definitionId(), publication.versionLabel(), publication.contentFingerprint(),
				publication.manifestIdentity(), publication.copyId(), publication.targetStorageId(),
				publication.location(), publication.verifiedBytes(), publication.verifiedAt(), List.of())))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("manifest must not be empty");
	}

	@Test
	void publicationRejectsAReusedDefinitionIdentityWithDifferentContent() {
		DatasetPublication publication = publication();
		this.catalog.publish(publication);

		assertThatThrownBy(
				() -> this.catalog.publish(new DatasetPublication(publication.datasetId(), publication.definitionId(),
						"v1", "different-fingerprint", publication.manifestIdentity(), publication.copyId(),
						publication.targetStorageId(), publication.location(), 42, NOW, publication.manifestEntries())))
			.isInstanceOf(DatasetCatalogConflictException.class)
			.hasMessageContaining("DATASET_DEFINITION_CONFLICT");
	}

	@Test
	void publicationRejectsAReusedDatasetVersionLabelWithDifferentContent() {
		DatasetPublication publication = publication();
		this.catalog.publish(publication);

		assertThatThrownBy(() -> this.catalog
			.publish(new DatasetPublication(publication.datasetId(), UUID.randomUUID(), publication.versionLabel(),
					"different-fingerprint", "different-manifest", UUID.randomUUID(), publication.targetStorageId(),
					"datasets/project/other", 7, NOW, List.of(new DatasetManifestEntry("other.bin", 7, "checksum")))))
			.isInstanceOf(DatasetCatalogConflictException.class)
			.hasMessageContaining("DATASET_VERSION_LABEL_CONFLICT");
	}

	@Test
	void leaseClaimsAnExactGenerationAndDeprecationRejectsLaterClaims() {
		DatasetCatalogView published = this.catalog.publish(publication());
		DatasetCopyView authority = published.copies().getFirst();
		UUID runId = UUID.randomUUID();

		DatasetLeaseView lease = this.catalog.acquireLease(published.definition().definitionId(), authority.id(), 1,
				published.revision(), runId);
		DatasetCatalogView deprecated = this.catalog.deprecateGeneration(published.definition().definitionId(),
				authority.id(), 1, 2);

		assertThat(lease.runRecordId()).isEqualTo(runId);
		assertThat(lease.generation()).isEqualTo(1);
		assertThat(deprecated.copies().getFirst().activeLeaseCount()).isEqualTo(1);
		assertThat(this.catalog.eligibleCopies(published.definition().definitionId())).isEmpty();
		assertThatThrownBy(() -> this.catalog.acquireLease(published.definition().definitionId(), authority.id(), 1,
				deprecated.revision(), UUID.randomUUID()))
			.isInstanceOf(DatasetCatalogConflictException.class)
			.hasMessageContaining("DATASET_COPY_INELIGIBLE");
	}

	@Test
	void promotionRevalidatesTheReplicaAndSwapsRolesWithoutEndingExistingLeases() {
		DatasetCatalogView published = this.catalog.publish(publication());
		DatasetCopyView authority = published.copies().getFirst();
		this.catalog.acquireLease(published.definition().definitionId(), authority.id(), 1, published.revision(),
				UUID.randomUUID());
		UUID replicaId = UUID.randomUUID();
		DatasetCatalogView withReplica = this.catalog.addReplica(published.definition().definitionId(),
				new DatasetReplicaPublication(replicaId, UUID.randomUUID(), "replicas/project/v1", 42, NOW), 2);

		DatasetCatalogView promoted = this.catalog.promote(published.definition().definitionId(), replicaId,
				withReplica.revision());

		assertThat(promoted.copies()).filteredOn(copy -> copy.role() == DatasetCopyRole.AUTHORITY)
			.singleElement()
			.extracting(DatasetCopyView::id)
			.isEqualTo(replicaId);
		assertThat(promoted.copies()).filteredOn(copy -> copy.id().equals(authority.id()))
			.singleElement()
			.satisfies(copy -> {
				assertThat(copy.role()).isEqualTo(DatasetCopyRole.REPLICA);
				assertThat(copy.activeLeaseCount()).isEqualTo(1);
			});
	}

	@Test
	void refreshWaitsForLeasesAndTerminalEvidenceReleasesItToTransfer() {
		DatasetCatalogView published = this.catalog.publish(publication());
		DatasetCopyView authority = published.copies().getFirst();
		DatasetLeaseView lease = this.catalog.acquireLease(published.definition().definitionId(), authority.id(), 1,
				published.revision(), UUID.randomUUID());

		DatasetCopyOperationView operation = this.catalog.startRefresh(published.definition().definitionId(),
				authority.id(), 1, 2);
		DatasetCopyOperationView released = this.catalog.endLease(published.definition().definitionId(), lease.id(),
				RunTerminalEvidence.FINISHED, 3);

		assertThat(operation.progress()).isEqualTo(DatasetCopyOperationProgress.WAITING_FOR_LEASES);
		assertThat(released.id()).isEqualTo(operation.id());
		assertThat(released.progress()).isEqualTo(DatasetCopyOperationProgress.TRANSFERRING);
	}

	@Test
	void failedOperationRetriesUnderTheSameIdentity() {
		DatasetCatalogView published = this.catalog.publish(publication());
		DatasetCopyView authority = published.copies().getFirst();
		DatasetCopyOperationView started = this.catalog.startRefresh(published.definition().definitionId(),
				authority.id(), 1, published.revision());
		DatasetCopyOperationView failed = this.catalog.failOperation(published.definition().definitionId(),
				started.id(), "STORAGE_UNAVAILABLE", "Target Storage did not respond.", true, 2);

		DatasetCopyOperationView retried = this.catalog.retryOperation(published.definition().definitionId(),
				failed.id(), 3);

		assertThat(retried.id()).isEqualTo(started.id());
		assertThat(retried.attempts()).isEqualTo(2);
		assertThat(retried.progress()).isEqualTo(DatasetCopyOperationProgress.TRANSFERRING);
		assertThat(retried.failureSummary()).isNull();
	}

	@Test
	void failedCleanupRetriesCleanupWithoutPublishingAnotherGeneration() {
		DatasetPublication publication = publication();
		DatasetCatalogView published = this.catalog.publish(publication);
		DatasetCopyOperationView started = this.catalog.startRefresh(publication.definitionId(), publication.copyId(),
				1, published.revision());
		this.catalog.recordTransferComplete(publication.definitionId(), started.id(), 2);
		DatasetCopyOperationView cleanup = this.catalog.publishReplacement(publication.definitionId(), started.id(),
				new VerifiedDatasetReplacement("datasets/project/v1.refresh", 42, publication.manifestIdentity(),
						publication.contentFingerprint(), NOW),
				3);
		DatasetCopyOperationView failed = this.catalog.failOperation(publication.definitionId(), started.id(),
				"STORAGE_UNAVAILABLE", "Deletion failed.", true, 4);

		DatasetCopyOperationView retried = this.catalog.retryOperation(publication.definitionId(), failed.id(), 5);

		assertThat(cleanup.progress()).isEqualTo(DatasetCopyOperationProgress.DELETING_OLD_BYTES);
		assertThat(failed.failedProgress()).isEqualTo(DatasetCopyOperationProgress.DELETING_OLD_BYTES);
		assertThat(retried.progress()).isEqualTo(DatasetCopyOperationProgress.DELETING_OLD_BYTES);
		assertThat(this.catalog.get(publication.definitionId()).copies().getFirst().currentGeneration().number())
			.isEqualTo(2);
	}

	@Test
	void generationFactsDeriveLeaseCountAndLastRunUseFromExactGeneration() {
		DatasetPublication publication = publication();
		DatasetCatalogView published = this.catalog.publish(publication);
		DatasetLeaseView lease = this.catalog.acquireLease(publication.definitionId(), publication.copyId(), 1,
				published.revision(), UUID.randomUUID());

		DatasetCopyGenerationView leased = this.catalog.get(publication.definitionId())
			.copies()
			.getFirst()
			.currentGeneration();
		this.catalog.endLease(publication.definitionId(), lease.id(), RunTerminalEvidence.FINISHED, 2);
		DatasetCopyGenerationView ended = this.catalog.get(publication.definitionId())
			.copies()
			.getFirst()
			.currentGeneration();

		assertThat(leased.activeLeaseCount()).isEqualTo(1);
		assertThat(leased.lastRunUsedAt()).isEqualTo(NOW);
		assertThat(ended.activeLeaseCount()).isZero();
		assertThat(ended.lastRunUsedAt()).isEqualTo(NOW);
	}

	@Test
	void cancellingBeforePublicationRestoresTheOriginalLeaseAdmissionState() {
		DatasetCatalogView published = this.catalog.publish(publication());
		DatasetCopyView authority = published.copies().getFirst();
		DatasetCopyOperationView started = this.catalog.startRefresh(published.definition().definitionId(),
				authority.id(), 1, published.revision());

		this.catalog.cancelOperation(published.definition().definitionId(), started.id(), 2);

		assertThat(this.catalog.get(published.definition().definitionId())
			.copies()
			.getFirst()
			.currentGeneration()
			.acceptingLeases()).isTrue();
	}

	@Test
	void publicationAndLeaseAdmissionRequireAnEligibleDatasetTargetStorage() {
		DatasetTargetStorageEligibility eligibility = storageId -> false;
		DatasetCatalog guarded = new DatasetCatalog(this.repository, Clock.fixed(NOW, ZoneOffset.UTC),
				(definition, manifest, copy) -> {
				}, eligibility);

		assertThatThrownBy(() -> guarded.publish(publication())).isInstanceOf(DatasetCatalogConflictException.class)
			.hasMessageContaining("DATASET_TARGET_STORAGE_INELIGIBLE");
	}

	@Test
	void cacheReportsKeepImmutableOwnershipAndCannotChangeCopyRoles() {
		DatasetCatalogView published = this.catalog.publish(publication());
		UUID cacheId = UUID.randomUUID();
		DatasetCacheReport first = new DatasetCacheReport(cacheId, DatasetCacheOwnerType.HOST, "trainer-01", 1024, NOW,
				NOW);

		DatasetCatalogView reported = this.catalog.reportCache(published.definition().definitionId(), first,
				published.revision());

		assertThat(reported.caches()).singleElement().satisfies(cache -> {
			assertThat(cache.ownerType()).isEqualTo(DatasetCacheOwnerType.HOST);
			assertThat(cache.ownerId()).isEqualTo("trainer-01");
			assertThat(cache.measuredBytes()).isEqualTo(1024);
		});
		assertThat(reported.copies()).singleElement()
			.extracting(DatasetCopyView::role)
			.isEqualTo(DatasetCopyRole.AUTHORITY);
		assertThatThrownBy(() -> this.catalog.reportCache(published.definition().definitionId(),
				new DatasetCacheReport(cacheId, DatasetCacheOwnerType.RUN, UUID.randomUUID().toString(), 1024, NOW,
						NOW),
				reported.revision()))
			.isInstanceOf(DatasetCatalogConflictException.class)
			.hasMessageContaining("DATASET_CACHE_OWNER_CONFLICT");
	}

	@Test
	void refreshPublishesVerifiedReplacementBeforeOldByteCleanupCompletes() {
		DatasetCatalogView published = this.catalog.publish(publication());
		DatasetCopyView authority = published.copies().getFirst();
		DatasetCopyOperationView operation = this.catalog.startRefresh(published.definition().definitionId(),
				authority.id(), 1, published.revision());
		this.catalog.recordTransferComplete(published.definition().definitionId(), operation.id(), 2);

		DatasetCopyOperationView cleanup = this.catalog.publishReplacement(published.definition().definitionId(),
				operation.id(), new VerifiedDatasetReplacement("datasets/project/v1-generation-2", 42,
						publication().manifestIdentity(), publication().contentFingerprint(), NOW),
				3);
		DatasetCatalogView duringCleanup = this.catalog.get(published.definition().definitionId());

		assertThat(cleanup.progress()).isEqualTo(DatasetCopyOperationProgress.DELETING_OLD_BYTES);
		assertThat(duringCleanup.copies().getFirst().currentGeneration().number()).isEqualTo(2);
		assertThat(duringCleanup.copies().getFirst().generationHistory()).extracting(DatasetCopyGenerationView::number)
			.containsExactly(1L, 2L);
	}

	@Test
	void maintenanceWorkerResumesEveryDurableRefreshStage() {
		DatasetPublication publication = new DatasetPublication(UUID.randomUUID(), UUID.randomUUID(), "v1",
				"sha256:content", "sha256:manifest", UUID.randomUUID(), UUID.randomUUID(), "datasets/project/v1", 42,
				NOW, List.of(new DatasetManifestEntry("shard.bin", 42, "checksum")));
		DatasetCatalogView published = this.catalog.publish(publication);
		DatasetCopyOperationView operation = this.catalog.startRefresh(publication.definitionId(), publication.copyId(),
				1, published.revision());
		DatasetCopyStorage storage = new DatasetCopyStorage() {
			@Override
			public void verify(DatasetDefinitionView definition, List<DatasetManifestEntry> manifest,
					DatasetCopyView copy) {
			}

			@Override
			public VerifiedDatasetReplacement stageReplacement(DatasetDefinitionView definition,
					List<DatasetManifestEntry> manifest, DatasetCopyView copy, UUID operationId) {
				return replacement(definition, copy, operationId);
			}

			@Override
			public VerifiedDatasetReplacement verifyReplacement(DatasetDefinitionView definition,
					List<DatasetManifestEntry> manifest, DatasetCopyView copy, UUID operationId) {
				return replacement(definition, copy, operationId);
			}

			@Override
			public void deleteAndVerify(List<DatasetManifestEntry> manifest, DatasetCopyView copy, long generation) {
			}

			private VerifiedDatasetReplacement replacement(DatasetDefinitionView definition, DatasetCopyView copy,
					UUID operationId) {
				return new VerifiedDatasetReplacement(copy.currentGeneration().location() + ".refresh-" + operationId,
						42, definition.manifestIdentity(), definition.contentFingerprint(), NOW);
			}
		};
		DatasetCopyMaintenanceWorker worker = new DatasetCopyMaintenanceWorker(this.catalog, storage);

		worker.resumeDurableOperations();
		worker.resumeDurableOperations();
		worker.resumeDurableOperations();

		DatasetCopyOperationView completed = this.catalog.getOperation(publication.definitionId(), operation.id());
		assertThat(completed.progress()).isEqualTo(DatasetCopyOperationProgress.COMPLETED);
		assertThat(this.catalog.get(publication.definitionId()).copies().getFirst().currentGeneration().number())
			.isEqualTo(2);
	}

	@Test
	void storageRecoveryDoesNotUndoGenerationDeprecation() {
		DatasetCatalogView published = this.catalog.publish(publication());
		DatasetCopyView authority = published.copies().getFirst();
		this.catalog.deprecateGeneration(published.definition().definitionId(), authority.id(), 1,
				published.revision());

		DatasetCatalogView recovered = this.catalog.reportAvailability(published.definition().definitionId(),
				authority.id(), 1, DatasetCopyAvailability.AVAILABLE, 2);

		assertThat(recovered.copies().getFirst().currentGeneration().availability())
			.isEqualTo(DatasetCopyAvailability.AVAILABLE);
		assertThat(recovered.copies().getFirst().currentGeneration().acceptingLeases()).isFalse();
	}

	private static DatasetPublication publication() {
		return new DatasetPublication(UUID.fromString("00000000-0000-0000-0000-000000000001"),
				UUID.fromString("00000000-0000-0000-0000-000000000002"), "v1", "sha256:content", "sha256:manifest",
				UUID.fromString("00000000-0000-0000-0000-000000000003"),
				UUID.fromString("00000000-0000-0000-0000-000000000004"), "datasets/project/v1", 42, NOW,
				List.of(new DatasetManifestEntry("shard.bin", 42, "checksum")));
	}

}
