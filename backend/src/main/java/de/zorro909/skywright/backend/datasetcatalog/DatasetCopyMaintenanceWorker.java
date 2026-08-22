package de.zorro909.skywright.backend.datasetcatalog;

import org.springframework.scheduling.annotation.Scheduled;

final class DatasetCopyMaintenanceWorker {

	private final DatasetCatalog catalog;

	private final DatasetCopyStorage storage;

	DatasetCopyMaintenanceWorker(DatasetCatalog catalog, DatasetCopyStorage storage) {
		this.catalog = catalog;
		this.storage = storage;
	}

	@Scheduled(fixedDelayString = "${skywright.dataset-catalog.maintenance-delay:PT5S}")
	void resumeDurableOperations() {
		java.util.List<DatasetCopyWorkItem> workItems;
		try {
			workItems = this.catalog.maintenanceWork();
		}
		catch (RuntimeException databaseUnavailable) {
			return;
		}
		for (DatasetCopyWorkItem work : workItems) {
			this.advance(work);
		}
	}

	private void advance(DatasetCopyWorkItem work) {
		DatasetCopyOperationView operation = work.operation();
		try {
			switch (operation.progress()) {
				case TRANSFERRING -> {
					this.storage.stageReplacement(work.definition(), work.manifest(), work.copy(), operation.id());
					this.catalog.recordTransferComplete(work.definition().definitionId(), operation.id(),
							work.catalogRevision());
				}
				case VERIFYING -> {
					VerifiedDatasetReplacement replacement = this.storage.verifyReplacement(work.definition(),
							work.manifest(), work.copy(), operation.id());
					this.catalog.publishReplacement(work.definition().definitionId(), operation.id(), replacement,
							work.catalogRevision());
				}
				case DELETING_OLD_BYTES -> {
					this.storage.deleteAndVerify(work.manifest(), work.copy(), operation.generation());
					this.catalog.recordAbsenceVerified(work.definition().definitionId(), operation.id(),
							work.catalogRevision());
				}
				default -> {
				}
			}
		}
		catch (RuntimeException failure) {
			this.recordFailure(work, failure);
		}
	}

	private void recordFailure(DatasetCopyWorkItem work, RuntimeException failure) {
		String code = failure instanceof DatasetCatalogException catalogFailure ? catalogFailure.errorCode()
				: "DATASET_STORAGE_UNAVAILABLE";
		try {
			this.catalog.failOperation(work.definition().definitionId(), work.operation().id(), code,
					"Dataset storage maintenance failed; retry the operation.", true, work.catalogRevision());
		}
		catch (RuntimeException concurrentProgress) {
			// Another backend advanced the durable operation; its committed state wins.
		}
	}

}
