package de.zorro909.skywright.backend.datasetcatalog;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

@Transactional
public class DatasetCatalog {

	private final DatasetCatalogRepository repository;

	private final Clock clock;

	private final DatasetCopyVerifier verifier;

	private final DatasetTargetStorageEligibility storageEligibility;

	DatasetCatalog(DatasetCatalogRepository repository, Clock clock) {
		this(repository, clock, (definition, manifest, copy) -> {
			if (!definition.manifestIdentity().equals(copy.currentGeneration().manifestIdentity())
					|| !definition.contentFingerprint().equals(copy.currentGeneration().contentFingerprint())) {
				throw new DatasetCatalogConflictException("DATASET_COPY_MANIFEST_MISMATCH",
						"Dataset Copy does not match the Dataset Definition manifest");
			}
		}, storageId -> true);
	}

	public DatasetCatalog(DatasetCatalogRepository repository, Clock clock, DatasetCopyVerifier verifier) {
		this(repository, clock, verifier, storageId -> true);
	}

	public DatasetCatalog(DatasetCatalogRepository repository, Clock clock, DatasetCopyVerifier verifier,
			DatasetTargetStorageEligibility storageEligibility) {
		this.repository = repository;
		this.clock = clock;
		this.verifier = verifier;
		this.storageEligibility = storageEligibility;
	}

	public DatasetCatalogView publish(DatasetPublication request) {
		this.requireEligibleStorage(request.targetStorageId());
		DatasetCatalogAggregate byDefinition = this.repository.findByDefinitionId(request.definitionId()).orElse(null);
		if (byDefinition != null) {
			if (!byDefinition.isSamePublication(request)) {
				throw new DatasetCatalogConflictException("DATASET_DEFINITION_CONFLICT",
						"Dataset Definition identity is already assigned to different content");
			}
			return byDefinition.view();
		}
		if (request.versionLabel() != null) {
			this.repository.findByDatasetAndVersionLabel(request.datasetId(), request.versionLabel())
				.ifPresent(existing -> {
					if (!existing.isSamePublication(request)) {
						throw new DatasetCatalogConflictException("DATASET_VERSION_LABEL_CONFLICT",
								"Dataset version label is already assigned to different content");
					}
				});
		}
		DatasetCatalogAggregate created = DatasetCatalogAggregate.publish(request, this.clock.instant());
		this.repository.save(created);
		return created.view();
	}

	public DatasetLeaseView acquireLease(UUID definitionId, UUID copyId, long generation, long expectedRevision,
			UUID runRecordId) {
		DatasetCatalogAggregate catalog = this.catalog(definitionId);
		this.requireEligibleStorage(catalog.targetStorageId(copyId));
		DatasetLeaseView lease = catalog.acquireLease(copyId, generation, expectedRevision, runRecordId,
				this.clock.instant());
		this.repository.save(catalog);
		return lease;
	}

	public DatasetCatalogView deprecateGeneration(UUID definitionId, UUID copyId, long generation,
			long expectedRevision) {
		DatasetCatalogAggregate catalog = this.catalog(definitionId);
		catalog.deprecate(copyId, generation, expectedRevision);
		this.repository.save(catalog);
		return catalog.view();
	}

	public DatasetCatalogView addReplica(UUID definitionId, DatasetReplicaPublication request, long expectedRevision) {
		this.requireEligibleStorage(request.targetStorageId());
		DatasetCatalogAggregate catalog = this.catalog(definitionId);
		catalog.addReplica(request, expectedRevision, this.clock.instant());
		this.repository.save(catalog);
		return catalog.view();
	}

	public DatasetCatalogView promote(UUID definitionId, UUID copyId, long expectedRevision) {
		DatasetCatalogAggregate catalog = this.catalog(definitionId);
		this.requireEligibleStorage(catalog.targetStorageId(copyId));
		catalog.promote(copyId, expectedRevision, this.verifier);
		this.repository.save(catalog);
		return catalog.view();
	}

	public DatasetCopyOperationView startRefresh(UUID definitionId, UUID copyId, long generation,
			long expectedRevision) {
		DatasetCatalogAggregate catalog = this.catalog(definitionId);
		DatasetCopyOperationView operation = catalog.startRefresh(copyId, generation, expectedRevision,
				this.clock.instant());
		this.repository.save(catalog);
		return operation;
	}

	public DatasetCopyOperationView endLease(UUID definitionId, UUID leaseId, RunTerminalEvidence evidence,
			long expectedRevision) {
		DatasetCatalogAggregate catalog = this.catalog(definitionId);
		DatasetCopyOperationView operation = catalog.endLease(leaseId, evidence, expectedRevision,
				this.clock.instant());
		this.repository.save(catalog);
		return operation;
	}

	public DatasetCopyOperationView startDelete(UUID definitionId, UUID copyId, long generation,
			long expectedRevision) {
		DatasetCatalogAggregate catalog = this.catalog(definitionId);
		DatasetCopyOperationView operation = catalog.startDelete(copyId, generation, expectedRevision,
				this.clock.instant());
		this.repository.save(catalog);
		return operation;
	}

	public DatasetCopyOperationView failOperation(UUID definitionId, UUID operationId, String failureCode,
			String failureSummary, boolean retryable, long expectedRevision) {
		DatasetCatalogAggregate catalog = this.catalog(definitionId);
		DatasetCopyOperationView operation = catalog.failOperation(operationId, failureCode, failureSummary, retryable,
				expectedRevision, this.clock.instant());
		this.repository.save(catalog);
		return operation;
	}

	public DatasetCopyOperationView retryOperation(UUID definitionId, UUID operationId, long expectedRevision) {
		DatasetCatalogAggregate catalog = this.catalog(definitionId);
		DatasetCopyOperationView operation = catalog.retryOperation(operationId, expectedRevision,
				this.clock.instant());
		this.repository.save(catalog);
		return operation;
	}

	public DatasetCopyOperationView cancelOperation(UUID definitionId, UUID operationId, long expectedRevision) {
		DatasetCatalogAggregate catalog = this.catalog(definitionId);
		DatasetCopyOperationView operation = catalog.cancelOperation(operationId, expectedRevision,
				this.clock.instant());
		this.repository.save(catalog);
		return operation;
	}

	@Transactional(readOnly = true)
	public DatasetCopyOperationView getOperation(UUID definitionId, UUID operationId) {
		return this.catalog(definitionId).operationView(operationId);
	}

	public DatasetCatalogView reportCache(UUID definitionId, DatasetCacheReport report, long expectedRevision) {
		DatasetCatalogAggregate catalog = this.catalog(definitionId);
		catalog.reportCache(report, expectedRevision, this.clock.instant());
		this.repository.save(catalog);
		return catalog.view();
	}

	public DatasetCatalogView removeCache(UUID definitionId, UUID cacheId, long expectedRevision) {
		DatasetCatalogAggregate catalog = this.catalog(definitionId);
		catalog.removeCache(cacheId, expectedRevision);
		this.repository.save(catalog);
		return catalog.view();
	}

	public DatasetCopyOperationView recordTransferComplete(UUID definitionId, UUID operationId, long expectedRevision) {
		DatasetCatalogAggregate catalog = this.catalog(definitionId);
		DatasetCopyOperationView operation = catalog.recordTransferComplete(operationId, expectedRevision,
				this.clock.instant());
		this.repository.save(catalog);
		return operation;
	}

	public DatasetCopyOperationView publishReplacement(UUID definitionId, UUID operationId,
			VerifiedDatasetReplacement replacement, long expectedRevision) {
		DatasetCatalogAggregate catalog = this.catalog(definitionId);
		DatasetCopyOperationView operation = catalog.publishReplacement(operationId, replacement, expectedRevision,
				this.clock.instant());
		this.repository.save(catalog);
		return operation;
	}

	public DatasetCopyOperationView recordAbsenceVerified(UUID definitionId, UUID operationId, long expectedRevision) {
		DatasetCatalogAggregate catalog = this.catalog(definitionId);
		DatasetCopyOperationView operation = catalog.recordAbsenceVerified(operationId, expectedRevision,
				this.clock.instant());
		this.repository.save(catalog);
		return operation;
	}

	public DatasetCatalogView reportAvailability(UUID definitionId, UUID copyId, long generation,
			DatasetCopyAvailability availability, long expectedRevision) {
		DatasetCatalogAggregate catalog = this.catalog(definitionId);
		catalog.reportAvailability(copyId, generation, availability, expectedRevision);
		this.repository.save(catalog);
		return catalog.view();
	}

	@Transactional(readOnly = true)
	public DatasetCatalogView get(UUID definitionId) {
		return this.catalog(definitionId).view();
	}

	@Transactional(readOnly = true)
	public List<DatasetCopyView> eligibleCopies(UUID definitionId) {
		return this.catalog(definitionId).view().copies().stream().filter(this::eligible).toList();
	}

	@Transactional(readOnly = true)
	public List<DatasetCatalogView> list() {
		return this.repository.findAll().stream().map(DatasetCatalogAggregate::view).toList();
	}

	@Transactional(readOnly = true)
	List<DatasetCopyWorkItem> maintenanceWork() {
		return this.repository.findAll()
			.stream()
			.flatMap(aggregate -> aggregate.view()
				.operations()
				.stream()
				.filter(DatasetCopyOperationView::active)
				.filter(operation -> operation.progress() != DatasetCopyOperationProgress.WAITING_FOR_LEASES)
				.map(operation -> new DatasetCopyWorkItem(aggregate.view().revision(), aggregate.view().definition(),
						aggregate.manifest(),
						aggregate.view()
							.copies()
							.stream()
							.filter(copy -> copy.id().equals(operation.copyId()))
							.findFirst()
							.orElseThrow(),
						operation)))
			.toList();
	}

	boolean eligible(DatasetCopyView copy) {
		return copy.currentGeneration().acceptingLeases()
				&& copy.currentGeneration().availability() == DatasetCopyAvailability.AVAILABLE
				&& this.storageEligibility.eligible(copy.targetStorageId());
	}

	private void requireEligibleStorage(UUID storageId) {
		if (!this.storageEligibility.eligible(storageId)) {
			throw new DatasetCatalogConflictException("DATASET_TARGET_STORAGE_INELIGIBLE",
					"Dataset Target Storage is not eligible for new work");
		}
	}

	private DatasetCatalogAggregate catalog(UUID definitionId) {
		return this.repository.findByDefinitionId(definitionId)
			.orElseThrow(() -> new DatasetCatalogNotFoundException(definitionId));
	}

}
