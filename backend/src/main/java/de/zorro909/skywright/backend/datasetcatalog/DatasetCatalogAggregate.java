package de.zorro909.skywright.backend.datasetcatalog;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

final class DatasetCatalogAggregate {

	private long revision;

	private final DatasetDefinitionView definition;

	private final List<DatasetCopyView> copies;

	private final List<DatasetManifestEntry> manifest;

	private final List<DatasetLeaseView> leases;

	private final List<DatasetCopyOperationView> operations;

	private final List<DatasetCacheView> caches;

	private DatasetCatalogAggregate(long revision, DatasetDefinitionView definition, List<DatasetCopyView> copies,
			List<DatasetManifestEntry> manifest, List<DatasetLeaseView> leases, List<DatasetCacheView> caches,
			List<DatasetCopyOperationView> operations) {
		this.revision = revision;
		this.definition = definition;
		this.copies = new ArrayList<>(copies);
		this.manifest = List.copyOf(manifest);
		this.leases = new ArrayList<>(leases);
		this.operations = new ArrayList<>(operations);
		this.caches = new ArrayList<>(caches);
	}

	static DatasetCatalogAggregate publish(DatasetPublication request, Instant createdAt) {
		Objects.requireNonNull(request.datasetId(), "datasetId");
		Objects.requireNonNull(request.definitionId(), "definitionId");
		requireText(request.contentFingerprint(), "contentFingerprint");
		requireText(request.manifestIdentity(), "manifestIdentity");
		requireText(request.location(), "location");
		if (request.verifiedBytes() < 0) {
			throw new IllegalArgumentException("verifiedBytes must not be negative");
		}
		validateManifest(request.manifestEntries(), request.verifiedBytes());
		DatasetDefinitionView definition = new DatasetDefinitionView(request.datasetId(), request.definitionId(),
				request.versionLabel(), request.contentFingerprint(), request.manifestIdentity(), createdAt);
		DatasetCopyGenerationView generation = new DatasetCopyGenerationView(1, request.location(),
				request.manifestIdentity(), request.contentFingerprint(), request.verifiedBytes(), createdAt,
				request.verifiedAt(), true, DatasetCopyAvailability.AVAILABLE);
		DatasetCopyView copy = new DatasetCopyView(request.copyId(), request.targetStorageId(),
				DatasetCopyRole.AUTHORITY, 1, generation, List.of(generation), 0);
		return new DatasetCatalogAggregate(1, definition, List.of(copy), request.manifestEntries(), List.of(),
				List.of(), List.of());
	}

	static DatasetCatalogAggregate restore(long revision, DatasetDefinitionView definition,
			List<DatasetCopyView> copies, List<DatasetManifestEntry> manifest, List<DatasetLeaseView> leases,
			List<DatasetCacheView> caches, List<DatasetCopyOperationView> operations) {
		return new DatasetCatalogAggregate(revision, definition, copies, manifest, leases, caches, operations);
	}

	boolean isSamePublication(DatasetPublication request) {
		DatasetCopyView authority = this.copies.getFirst();
		return this.definition.datasetId().equals(request.datasetId())
				&& this.definition.definitionId().equals(request.definitionId())
				&& Objects.equals(this.definition.versionLabel(), request.versionLabel())
				&& this.definition.contentFingerprint().equals(request.contentFingerprint())
				&& this.definition.manifestIdentity().equals(request.manifestIdentity())
				&& authority.id().equals(request.copyId())
				&& authority.targetStorageId().equals(request.targetStorageId())
				&& authority.currentGeneration().location().equals(request.location())
				&& authority.currentGeneration().verifiedBytes() == request.verifiedBytes()
				&& this.manifest.equals(request.manifestEntries());
	}

	DatasetCatalogView view() {
		return new DatasetCatalogView(this.revision, this.definition, List.copyOf(this.copies),
				List.copyOf(this.leases), List.copyOf(this.caches), List.copyOf(this.operations));
	}

	DatasetCatalogSnapshot snapshot() {
		return new DatasetCatalogSnapshot(this.revision, this.definition, List.copyOf(this.copies), this.manifest,
				List.copyOf(this.leases), List.copyOf(this.caches), List.copyOf(this.operations));
	}

	DatasetLeaseView acquireLease(UUID copyId, long generation, long expectedRevision, UUID runRecordId,
			Instant acquiredAt) {
		this.requireRevision(expectedRevision);
		DatasetCopyView copy = this.copy(copyId);
		DatasetCopyGenerationView selected = copy.generationHistory()
			.stream()
			.filter(value -> value.number() == generation)
			.findFirst()
			.orElseThrow(() -> new DatasetCatalogConflictException("DATASET_COPY_GENERATION_NOT_FOUND",
					"Dataset Copy generation does not exist"));
		if (!selected.acceptingLeases() || selected.availability() != DatasetCopyAvailability.AVAILABLE) {
			throw new DatasetCatalogConflictException("DATASET_COPY_INELIGIBLE",
					"Dataset Copy generation is not eligible for a new lease");
		}
		DatasetLeaseView existing = this.leases.stream()
			.filter(value -> value.runRecordId().equals(runRecordId) && value.active())
			.findFirst()
			.orElse(null);
		if (existing != null) {
			if (existing.copyId().equals(copyId) && existing.generation() == generation) {
				return existing;
			}
			throw new DatasetCatalogConflictException("DATASET_LEASE_CONFLICT",
					"Run Record already holds a different active Dataset Lease");
		}
		DatasetLeaseView lease = new DatasetLeaseView(UUID.randomUUID(), runRecordId, this.definition.definitionId(),
				copyId, generation, acquiredAt, null, null);
		this.leases.add(lease);
		this.replaceCopy(copy, withActiveLeaseCount(copy, copy.activeLeaseCount() + 1));
		this.revision++;
		return lease;
	}

	void deprecate(UUID copyId, long generation, long expectedRevision) {
		this.requireRevision(expectedRevision);
		DatasetCopyView copy = this.copy(copyId);
		List<DatasetCopyGenerationView> history = copy.generationHistory()
			.stream()
			.map(value -> value.number() == generation ? new DatasetCopyGenerationView(value.number(), value.location(),
					value.manifestIdentity(), value.contentFingerprint(), value.verifiedBytes(), value.createdAt(),
					value.verifiedAt(), false, value.availability()) : value)
			.toList();
		if (history.stream().noneMatch(value -> value.number() == generation)) {
			throw new DatasetCatalogConflictException("DATASET_COPY_GENERATION_NOT_FOUND",
					"Dataset Copy generation does not exist");
		}
		DatasetCopyGenerationView current = history.stream()
			.filter(value -> value.number() == copy.currentGeneration().number())
			.findFirst()
			.orElseThrow();
		this.replaceCopy(copy, new DatasetCopyView(copy.id(), copy.targetStorageId(), copy.role(), copy.revision() + 1,
				current, history, copy.activeLeaseCount()));
		this.revision++;
	}

	void addReplica(DatasetReplicaPublication request, long expectedRevision, Instant createdAt) {
		this.requireRevision(expectedRevision);
		if (this.copies.stream().anyMatch(value -> value.id().equals(request.copyId()))) {
			DatasetCopyView existing = this.copy(request.copyId());
			if (existing.targetStorageId().equals(request.targetStorageId())
					&& existing.currentGeneration().location().equals(request.location())
					&& existing.currentGeneration().verifiedBytes() == request.verifiedBytes()) {
				return;
			}
			throw new DatasetCatalogConflictException("DATASET_COPY_CONFLICT",
					"Dataset Copy identity is already assigned to another location");
		}
		DatasetCopyGenerationView generation = new DatasetCopyGenerationView(1, request.location(),
				this.definition.manifestIdentity(), this.definition.contentFingerprint(), request.verifiedBytes(),
				createdAt, request.verifiedAt(), true, DatasetCopyAvailability.AVAILABLE);
		this.copies.add(new DatasetCopyView(request.copyId(), request.targetStorageId(), DatasetCopyRole.REPLICA, 1,
				generation, List.of(generation), 0));
		this.revision++;
	}

	void promote(UUID copyId, long expectedRevision, DatasetCopyVerifier verifier) {
		this.requireRevision(expectedRevision);
		DatasetCopyView candidate = this.copy(copyId);
		if (candidate.role() != DatasetCopyRole.REPLICA || !candidate.currentGeneration().acceptingLeases()
				|| candidate.currentGeneration().availability() != DatasetCopyAvailability.AVAILABLE) {
			throw new DatasetCatalogConflictException("DATASET_COPY_INELIGIBLE",
					"Only an eligible verified replica can be promoted");
		}
		verifier.verify(this.definition, this.manifest, candidate);
		for (int index = 0; index < this.copies.size(); index++) {
			DatasetCopyView copy = this.copies.get(index);
			DatasetCopyRole role = copy.id().equals(copyId) ? DatasetCopyRole.AUTHORITY : DatasetCopyRole.REPLICA;
			this.copies.set(index, new DatasetCopyView(copy.id(), copy.targetStorageId(), role, copy.revision() + 1,
					copy.currentGeneration(), copy.generationHistory(), copy.activeLeaseCount()));
		}
		this.revision++;
	}

	DatasetCopyOperationView startRefresh(UUID copyId, long generation, long expectedRevision, Instant now) {
		this.requireNoActiveOperation(copyId);
		this.requireAcceptingGeneration(copyId, generation);
		this.deprecate(copyId, generation, expectedRevision);
		DatasetCopyView copy = this.copy(copyId);
		DatasetCopyOperationProgress progress = copy.activeLeaseCount() == 0 ? DatasetCopyOperationProgress.TRANSFERRING
				: DatasetCopyOperationProgress.WAITING_FOR_LEASES;
		DatasetCopyOperationView operation = new DatasetCopyOperationView(UUID.randomUUID(),
				DatasetCopyOperationKind.REFRESH, copyId, generation, progress, 1, null, null, false, now, now);
		this.operations.add(operation);
		return operation;
	}

	DatasetCopyOperationView endLease(UUID leaseId, RunTerminalEvidence evidence, long expectedRevision, Instant now) {
		this.requireRevision(expectedRevision);
		int leaseIndex = -1;
		for (int index = 0; index < this.leases.size(); index++) {
			if (this.leases.get(index).id().equals(leaseId)) {
				leaseIndex = index;
				break;
			}
		}
		if (leaseIndex < 0) {
			throw new DatasetCatalogConflictException("DATASET_LEASE_NOT_FOUND", "Dataset Lease does not exist");
		}
		DatasetLeaseView lease = this.leases.get(leaseIndex);
		if (!lease.active()) {
			return this.operations.stream()
				.filter(operation -> operation.copyId().equals(lease.copyId()))
				.findFirst()
				.orElse(null);
		}
		this.leases.set(leaseIndex, new DatasetLeaseView(lease.id(), lease.runRecordId(), lease.definitionId(),
				lease.copyId(), lease.generation(), lease.acquiredAt(), now, evidence.name().toLowerCase()));
		DatasetCopyView copy = this.copy(lease.copyId());
		this.replaceCopy(copy, withActiveLeaseCount(copy, copy.activeLeaseCount() - 1));
		DatasetCopyOperationView released = null;
		for (int index = 0; index < this.operations.size(); index++) {
			DatasetCopyOperationView operation = this.operations.get(index);
			if (operation.copyId().equals(copy.id())
					&& operation.progress() == DatasetCopyOperationProgress.WAITING_FOR_LEASES
					&& copy.activeLeaseCount() - 1 == 0) {
				DatasetCopyOperationProgress next = operation.kind() == DatasetCopyOperationKind.REFRESH
						? DatasetCopyOperationProgress.TRANSFERRING : DatasetCopyOperationProgress.DELETING_OLD_BYTES;
				released = new DatasetCopyOperationView(operation.id(), operation.kind(), operation.copyId(),
						operation.generation(), next, operation.attempts(), null, null, false, operation.startedAt(),
						now);
				this.operations.set(index, released);
			}
		}
		this.revision++;
		return released;
	}

	DatasetCopyOperationView startDelete(UUID copyId, long generation, long expectedRevision, Instant now) {
		this.requireNoActiveOperation(copyId);
		DatasetCopyView selected = this.copy(copyId);
		if (selected.role() == DatasetCopyRole.AUTHORITY) {
			throw new DatasetCatalogConflictException("DATASET_AUTHORITY_DELETE_FORBIDDEN",
					"The authoritative Dataset Copy cannot be deleted");
		}
		this.requireAcceptingGeneration(copyId, generation);
		this.deprecate(copyId, generation, expectedRevision);
		DatasetCopyOperationProgress progress = selected.activeLeaseCount() == 0
				? DatasetCopyOperationProgress.DELETING_OLD_BYTES : DatasetCopyOperationProgress.WAITING_FOR_LEASES;
		DatasetCopyOperationView operation = new DatasetCopyOperationView(UUID.randomUUID(),
				DatasetCopyOperationKind.DELETE, copyId, generation, progress, 1, null, null, false, now, now);
		this.operations.add(operation);
		return operation;
	}

	DatasetCopyOperationView failOperation(UUID operationId, String failureCode, String failureSummary,
			boolean retryable, long expectedRevision, Instant now) {
		this.requireRevision(expectedRevision);
		DatasetCopyOperationView operation = this.operation(operationId);
		if (!operation.active()) {
			throw new DatasetCatalogConflictException("DATASET_COPY_OPERATION_NOT_ACTIVE",
					"Only an active Dataset Copy Operation can fail");
		}
		DatasetCopyOperationView failed = new DatasetCopyOperationView(operation.id(), operation.kind(),
				operation.copyId(), operation.generation(), DatasetCopyOperationProgress.FAILED, operation.attempts(),
				failureCode, safeSummary(failureSummary), retryable, operation.startedAt(), now);
		this.replaceOperation(operation, failed);
		this.revision++;
		return failed;
	}

	DatasetCopyOperationView retryOperation(UUID operationId, long expectedRevision, Instant now) {
		this.requireRevision(expectedRevision);
		DatasetCopyOperationView operation = this.operation(operationId);
		if (operation.progress() != DatasetCopyOperationProgress.FAILED || !operation.retryable()) {
			throw new DatasetCatalogConflictException("DATASET_COPY_OPERATION_NOT_RETRYABLE",
					"Dataset Copy Operation is not retryable");
		}
		DatasetCopyView copy = this.copy(operation.copyId());
		DatasetCopyOperationProgress progress = copy.activeLeaseCount() > 0
				? DatasetCopyOperationProgress.WAITING_FOR_LEASES : operation.kind() == DatasetCopyOperationKind.REFRESH
						? DatasetCopyOperationProgress.TRANSFERRING : DatasetCopyOperationProgress.DELETING_OLD_BYTES;
		DatasetCopyOperationView retried = new DatasetCopyOperationView(operation.id(), operation.kind(),
				operation.copyId(), operation.generation(), progress, operation.attempts() + 1, null, null, false,
				operation.startedAt(), now);
		this.replaceOperation(operation, retried);
		this.revision++;
		return retried;
	}

	DatasetCopyOperationView cancelOperation(UUID operationId, long expectedRevision, Instant now) {
		this.requireRevision(expectedRevision);
		DatasetCopyOperationView operation = this.operation(operationId);
		if (operation.progress() != DatasetCopyOperationProgress.WAITING_FOR_LEASES
				&& operation.progress() != DatasetCopyOperationProgress.TRANSFERRING
				&& operation.progress() != DatasetCopyOperationProgress.VERIFYING) {
			throw new DatasetCatalogConflictException("DATASET_COPY_OPERATION_NOT_CANCELLABLE",
					"Dataset Copy Operation has crossed its cancellation boundary");
		}
		DatasetCopyOperationView cancelled = new DatasetCopyOperationView(operation.id(), operation.kind(),
				operation.copyId(), operation.generation(), DatasetCopyOperationProgress.CANCELLED,
				operation.attempts(), null, null, false, operation.startedAt(), now);
		this.replaceOperation(operation, cancelled);
		this.restoreLeaseAdmission(operation.copyId(), operation.generation());
		this.revision++;
		return cancelled;
	}

	DatasetCopyOperationView operationView(UUID operationId) {
		return this.operation(operationId);
	}

	void reportCache(DatasetCacheReport report, long expectedRevision, Instant now) {
		this.requireRevision(expectedRevision);
		if (report.measuredBytes() < 0 || report.ownerId() == null || report.ownerId().isBlank()) {
			throw new IllegalArgumentException("Dataset Cache report is invalid");
		}
		if (report.ownerType() == DatasetCacheOwnerType.RUN) {
			try {
				UUID.fromString(report.ownerId());
			}
			catch (IllegalArgumentException invalidRunIdentity) {
				throw new IllegalArgumentException("Run-owned Dataset Caches require a Run Record UUID");
			}
		}
		DatasetCacheView sameOwner = this.caches.stream()
			.filter(value -> value.ownerType() == report.ownerType() && value.ownerId().equals(report.ownerId()))
			.findFirst()
			.orElse(null);
		DatasetCacheView sameIdentity = this.caches.stream()
			.filter(value -> value.id().equals(report.cacheId()))
			.findFirst()
			.orElse(null);
		if (sameIdentity != null && (sameIdentity.ownerType() != report.ownerType()
				|| !sameIdentity.ownerId().equals(report.ownerId()))) {
			throw new DatasetCatalogConflictException("DATASET_CACHE_OWNER_CONFLICT",
					"Dataset Cache ownership is immutable");
		}
		if (sameOwner != null && !sameOwner.id().equals(report.cacheId())) {
			throw new DatasetCatalogConflictException("DATASET_CACHE_OWNER_CONFLICT",
					"Dataset Definition already has a cache for this owner");
		}
		DatasetCacheView updated = new DatasetCacheView(report.cacheId(), report.ownerType(), report.ownerId(),
				report.measuredBytes(), report.verifiedAt(), report.lastUsedAt(),
				sameIdentity == null ? now : sameIdentity.createdAt());
		if (sameIdentity == null) {
			this.caches.add(updated);
		}
		else {
			this.caches.set(this.caches.indexOf(sameIdentity), updated);
		}
		this.revision++;
	}

	void removeCache(UUID cacheId, long expectedRevision) {
		this.requireRevision(expectedRevision);
		if (!this.caches.removeIf(value -> value.id().equals(cacheId))) {
			throw new DatasetCatalogConflictException("DATASET_CACHE_NOT_FOUND", "Dataset Cache does not exist");
		}
		this.revision++;
	}

	DatasetCopyOperationView recordTransferComplete(UUID operationId, long expectedRevision, Instant now) {
		this.requireRevision(expectedRevision);
		DatasetCopyOperationView operation = this.operation(operationId);
		if (operation.kind() != DatasetCopyOperationKind.REFRESH
				|| operation.progress() != DatasetCopyOperationProgress.TRANSFERRING) {
			throw new DatasetCatalogConflictException("DATASET_COPY_OPERATION_PROGRESS_CONFLICT",
					"Operation is not transferring a refresh replacement");
		}
		DatasetCopyOperationView verifying = withProgress(operation, DatasetCopyOperationProgress.VERIFYING, now);
		this.replaceOperation(operation, verifying);
		this.revision++;
		return verifying;
	}

	DatasetCopyOperationView publishReplacement(UUID operationId, VerifiedDatasetReplacement replacement,
			long expectedRevision, Instant now) {
		this.requireRevision(expectedRevision);
		DatasetCopyOperationView operation = this.operation(operationId);
		if (operation.kind() != DatasetCopyOperationKind.REFRESH
				|| operation.progress() != DatasetCopyOperationProgress.VERIFYING) {
			throw new DatasetCatalogConflictException("DATASET_COPY_OPERATION_PROGRESS_CONFLICT",
					"Operation has not completed replacement verification");
		}
		if (!this.definition.manifestIdentity().equals(replacement.manifestIdentity())
				|| !this.definition.contentFingerprint().equals(replacement.contentFingerprint())) {
			throw new DatasetCatalogConflictException("DATASET_COPY_MANIFEST_MISMATCH",
					"Replacement does not match the Dataset Definition integrity manifest");
		}
		DatasetCopyView copy = this.copy(operation.copyId());
		long generationNumber = copy.generationHistory()
			.stream()
			.mapToLong(DatasetCopyGenerationView::number)
			.max()
			.orElseThrow() + 1;
		DatasetCopyGenerationView generation = new DatasetCopyGenerationView(generationNumber, replacement.location(),
				replacement.manifestIdentity(), replacement.contentFingerprint(), replacement.verifiedBytes(), now,
				replacement.verifiedAt(), true, DatasetCopyAvailability.AVAILABLE);
		List<DatasetCopyGenerationView> history = new ArrayList<>(copy.generationHistory());
		history.add(generation);
		this.replaceCopy(copy, new DatasetCopyView(copy.id(), copy.targetStorageId(), copy.role(), copy.revision() + 1,
				generation, List.copyOf(history), copy.activeLeaseCount()));
		DatasetCopyOperationView cleanup = withProgress(operation, DatasetCopyOperationProgress.DELETING_OLD_BYTES,
				now);
		this.replaceOperation(operation, cleanup);
		this.revision++;
		return cleanup;
	}

	DatasetCopyOperationView recordAbsenceVerified(UUID operationId, long expectedRevision, Instant now) {
		this.requireRevision(expectedRevision);
		DatasetCopyOperationView operation = this.operation(operationId);
		if (operation.progress() != DatasetCopyOperationProgress.DELETING_OLD_BYTES) {
			throw new DatasetCatalogConflictException("DATASET_COPY_OPERATION_PROGRESS_CONFLICT",
					"Operation is not waiting for verified object absence");
		}
		if (operation.kind() == DatasetCopyOperationKind.DELETE) {
			this.copies.removeIf(copy -> copy.id().equals(operation.copyId()));
		}
		DatasetCopyOperationView completed = withProgress(operation, DatasetCopyOperationProgress.COMPLETED, now);
		this.replaceOperation(operation, completed);
		this.revision++;
		return completed;
	}

	void reportAvailability(UUID copyId, long generation, DatasetCopyAvailability availability, long expectedRevision) {
		this.requireRevision(expectedRevision);
		DatasetCopyView copy = this.copy(copyId);
		List<DatasetCopyGenerationView> history = copy.generationHistory()
			.stream()
			.map(value -> value.number() == generation ? new DatasetCopyGenerationView(value.number(), value.location(),
					value.manifestIdentity(), value.contentFingerprint(), value.verifiedBytes(), value.createdAt(),
					value.verifiedAt(), value.acceptingLeases(), availability) : value)
			.toList();
		if (history.stream().noneMatch(value -> value.number() == generation)) {
			throw new DatasetCatalogConflictException("DATASET_COPY_GENERATION_NOT_FOUND",
					"Dataset Copy generation does not exist");
		}
		DatasetCopyGenerationView current = history.stream()
			.filter(value -> value.number() == copy.currentGeneration().number())
			.findFirst()
			.orElseThrow();
		this.replaceCopy(copy, new DatasetCopyView(copy.id(), copy.targetStorageId(), copy.role(), copy.revision() + 1,
				current, history, copy.activeLeaseCount()));
		this.revision++;
	}

	private void requireNoActiveOperation(UUID copyId) {
		if (this.operations.stream().anyMatch(value -> value.copyId().equals(copyId) && value.active())) {
			throw new DatasetCatalogConflictException("DATASET_COPY_OPERATION_CONFLICT",
					"Dataset Copy already has an active maintenance operation");
		}
	}

	private void requireAcceptingGeneration(UUID copyId, long generation) {
		DatasetCopyGenerationView selected = this.copy(copyId)
			.generationHistory()
			.stream()
			.filter(value -> value.number() == generation)
			.findFirst()
			.orElseThrow(() -> new DatasetCatalogConflictException("DATASET_COPY_GENERATION_NOT_FOUND",
					"Dataset Copy generation does not exist"));
		if (!selected.acceptingLeases()) {
			throw new DatasetCatalogConflictException("DATASET_COPY_INELIGIBLE",
					"Deprecated Dataset Copy generations cannot start maintenance");
		}
	}

	private void restoreLeaseAdmission(UUID copyId, long generation) {
		DatasetCopyView copy = this.copy(copyId);
		List<DatasetCopyGenerationView> history = copy.generationHistory()
			.stream()
			.map(value -> value.number() == generation ? new DatasetCopyGenerationView(value.number(), value.location(),
					value.manifestIdentity(), value.contentFingerprint(), value.verifiedBytes(), value.createdAt(),
					value.verifiedAt(), true, value.availability()) : value)
			.toList();
		DatasetCopyGenerationView current = history.stream()
			.filter(value -> value.number() == copy.currentGeneration().number())
			.findFirst()
			.orElseThrow();
		this.replaceCopy(copy, new DatasetCopyView(copy.id(), copy.targetStorageId(), copy.role(), copy.revision() + 1,
				current, history, copy.activeLeaseCount()));
	}

	UUID targetStorageId(UUID copyId) {
		return this.copy(copyId).targetStorageId();
	}

	List<DatasetManifestEntry> manifest() {
		return this.manifest;
	}

	private static void validateManifest(List<DatasetManifestEntry> manifest, long verifiedBytes) {
		if (manifest.isEmpty()) {
			return;
		}
		long bytes = 0;
		java.util.HashSet<String> keys = new java.util.HashSet<>();
		for (DatasetManifestEntry entry : manifest) {
			requireText(entry.objectKey(), "manifest.objectKey");
			requireText(entry.checksumSha256(), "manifest.checksumSha256");
			if (entry.objectKey().startsWith("/") || entry.objectKey().contains("..") || entry.byteCount() < 0
					|| !keys.add(entry.objectKey())) {
				throw new IllegalArgumentException("Dataset integrity manifest is invalid");
			}
			bytes = Math.addExact(bytes, entry.byteCount());
		}
		if (bytes != verifiedBytes) {
			throw new IllegalArgumentException("verifiedBytes must equal the Dataset integrity manifest size");
		}
	}

	private DatasetCopyOperationView operation(UUID operationId) {
		return this.operations.stream()
			.filter(value -> value.id().equals(operationId))
			.findFirst()
			.orElseThrow(() -> new DatasetCatalogConflictException("DATASET_COPY_OPERATION_NOT_FOUND",
					"Dataset Copy Operation does not exist"));
	}

	private void replaceOperation(DatasetCopyOperationView oldValue, DatasetCopyOperationView newValue) {
		this.operations.set(this.operations.indexOf(oldValue), newValue);
	}

	private static String safeSummary(String value) {
		if (value == null) {
			return null;
		}
		return value.length() <= 1024 ? value : value.substring(0, 1024);
	}

	private static DatasetCopyOperationView withProgress(DatasetCopyOperationView operation,
			DatasetCopyOperationProgress progress, Instant now) {
		return new DatasetCopyOperationView(operation.id(), operation.kind(), operation.copyId(),
				operation.generation(), progress, operation.attempts(), null, null, false, operation.startedAt(), now);
	}

	private DatasetCopyView copy(UUID copyId) {
		return this.copies.stream()
			.filter(value -> value.id().equals(copyId))
			.findFirst()
			.orElseThrow(
					() -> new DatasetCatalogConflictException("DATASET_COPY_NOT_FOUND", "Dataset Copy does not exist"));
	}

	private void replaceCopy(DatasetCopyView oldValue, DatasetCopyView newValue) {
		this.copies.set(this.copies.indexOf(oldValue), newValue);
	}

	private void requireRevision(long expectedRevision) {
		if (this.revision != expectedRevision) {
			throw new DatasetCatalogConflictException("DATASET_CATALOG_REVISION_CONFLICT",
					"Dataset Catalog changed concurrently");
		}
	}

	private static DatasetCopyView withActiveLeaseCount(DatasetCopyView copy, long count) {
		return new DatasetCopyView(copy.id(), copy.targetStorageId(), copy.role(), copy.revision(),
				copy.currentGeneration(), copy.generationHistory(), count);
	}

	private static void requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
	}

}
