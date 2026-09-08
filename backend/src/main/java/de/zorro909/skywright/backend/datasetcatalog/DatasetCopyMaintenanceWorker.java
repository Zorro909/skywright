package de.zorro909.skywright.backend.datasetcatalog;

import org.springframework.scheduling.annotation.Scheduled;

final class DatasetCopyMaintenanceWorker {

	private final DatasetCatalog catalog;

	private final DatasetCopyStorage storage;

	private final java.util.concurrent.atomic.AtomicReference<Active> active = new java.util.concurrent.atomic.AtomicReference<>();

	private volatile boolean closing;

	DatasetCopyMaintenanceWorker(DatasetCatalog catalog, DatasetCopyStorage storage) {
		this.catalog = catalog;
		this.storage = storage;
	}

	@Scheduled(fixedDelayString = "${skywright.dataset-catalog.maintenance-delay:PT5S}")
	synchronized void resumeDurableOperations() {
		if (this.closing)
			return;
		java.util.List<DatasetCopyWorkItem> workItems;
		try {
			workItems = this.catalog.maintenanceWork();
		}
		catch (RuntimeException databaseUnavailable) {
			return;
		}
		Active running = this.active.get();
		if (running != null) {
			boolean current = workItems.stream()
				.anyMatch(work -> work.operation().id().equals(running.work().operation().id())
						&& work.operation().attempts() == running.work().operation().attempts()
						&& work.operation().progress() == running.work().operation().progress());
			if (!current)
				running.thread().interrupt();
			return;
		}
		if (!this.storage.ready() || workItems.isEmpty())
			return;
		var work = workItems.getFirst();
		Thread thread = Thread.ofPlatform().daemon().name("dataset-copy-maintenance").unstarted(() -> {
			try {
				this.advance(work);
			}
			finally {
				this.active.set(null);
			}
		});
		this.active.set(new Active(work, thread));
		thread.start();
	}

	@jakarta.annotation.PreDestroy
	synchronized void close() {
		this.closing = true;
		Active running = this.active.get();
		if (running != null)
			running.thread().interrupt();
	}

	private record Active(DatasetCopyWorkItem work, Thread thread) {
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
					if (operation.kind() == DatasetCopyOperationKind.PROMOTE) {
						this.storage.verify(work.definition(), work.manifest(), work.copy());
						this.catalog.completePromotion(work.definition().definitionId(), operation.id(),
								work.catalogRevision());
						break;
					}
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
