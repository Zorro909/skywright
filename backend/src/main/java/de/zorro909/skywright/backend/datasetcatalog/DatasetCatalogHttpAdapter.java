package de.zorro909.skywright.backend.datasetcatalog;

import de.zorro909.skywright.backend.boundary.generated.api.DatasetCatalogApi;
import de.zorro909.skywright.backend.boundary.generated.model.DatasetCatalogPage;
import de.zorro909.skywright.backend.boundary.generated.model.DatasetCatalogRecord;
import de.zorro909.skywright.backend.boundary.generated.model.DatasetCatalogRevisionCommand;
import de.zorro909.skywright.backend.boundary.generated.model.DatasetCopyOperation;
import de.zorro909.skywright.backend.boundary.generated.model.DatasetCopyRole;
import de.zorro909.skywright.backend.boundary.generated.model.StartDatasetCopyOperation;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DatasetCatalogHttpAdapter implements DatasetCatalogApi {

	private final DatasetCatalog catalog;

	DatasetCatalogHttpAdapter(DatasetCatalog catalog) {
		this.catalog = catalog;
	}

	@Override
	public ResponseEntity<DatasetCopyOperation> cancelDatasetCopyOperation(UUID definitionId, UUID operationId,
			DatasetCatalogRevisionCommand request) {
		return ResponseEntity
			.ok(this.operation(this.catalog.cancelOperation(definitionId, operationId, request.getExpectedRevision())));
	}

	@Override
	public ResponseEntity<DatasetCopyOperation> deleteDatasetCopy(UUID definitionId, UUID copyId,
			StartDatasetCopyOperation request) {
		return ResponseEntity.status(201)
			.body(this.operation(this.catalog.startDelete(definitionId, copyId, request.getGeneration(),
					request.getExpectedRevision())));
	}

	@Override
	public ResponseEntity<DatasetCatalogRecord> getDatasetCatalogRecord(UUID definitionId) {
		return ResponseEntity.ok(this.record(this.catalog.get(definitionId)));
	}

	@Override
	public ResponseEntity<DatasetCopyOperation> getDatasetCopyOperation(UUID definitionId, UUID operationId) {
		return ResponseEntity.ok(this.operation(this.catalog.getOperation(definitionId, operationId)));
	}

	@Override
	public ResponseEntity<DatasetCatalogPage> listDatasetCatalog(UUID datasetId, UUID targetStorageId,
			DatasetCopyRole role, Boolean acceptingLeases, String cursor, Integer limit) {
		UUID after = cursor == null ? null : cursor(cursor);
		List<DatasetCatalogView> matching = this.catalog.list()
			.stream()
			.filter(value -> datasetId == null || value.definition().datasetId().equals(datasetId))
			.filter(value -> after == null || value.definition().definitionId().compareTo(after) > 0)
			.filter(value -> targetStorageId == null
					|| value.copies().stream().anyMatch(copy -> copy.targetStorageId().equals(targetStorageId)))
			.filter(value -> role == null
					|| value.copies().stream().anyMatch(copy -> copy.role().name().equalsIgnoreCase(role.getValue())))
			.filter(value -> acceptingLeases == null || value.copies()
				.stream()
				.anyMatch(copy -> copy.currentGeneration().acceptingLeases() == acceptingLeases.booleanValue()))
			.sorted(Comparator.comparing(value -> value.definition().definitionId()))
			.limit((long) limit + 1)
			.toList();
		boolean hasNext = matching.size() > limit;
		List<DatasetCatalogView> page = hasNext ? matching.subList(0, limit) : matching;
		String nextCursor = hasNext ? page.getLast().definition().definitionId().toString() : null;
		return ResponseEntity.ok(new DatasetCatalogPage(page.stream().map(this::record).toList(), nextCursor));
	}

	@Override
	public ResponseEntity<DatasetCatalogRecord> promoteDatasetCopy(UUID definitionId, UUID copyId,
			DatasetCatalogRevisionCommand request) {
		return ResponseEntity
			.ok(this.record(this.catalog.promote(definitionId, copyId, request.getExpectedRevision())));
	}

	@Override
	public ResponseEntity<DatasetCopyOperation> refreshDatasetCopy(UUID definitionId, UUID copyId,
			StartDatasetCopyOperation request) {
		return ResponseEntity.status(201)
			.body(this.operation(this.catalog.startRefresh(definitionId, copyId, request.getGeneration(),
					request.getExpectedRevision())));
	}

	@Override
	public ResponseEntity<DatasetCopyOperation> retryDatasetCopyOperation(UUID definitionId, UUID operationId,
			DatasetCatalogRevisionCommand request) {
		return ResponseEntity
			.ok(this.operation(this.catalog.retryOperation(definitionId, operationId, request.getExpectedRevision())));
	}

	private DatasetCatalogRecord record(DatasetCatalogView value) {
		DatasetDefinitionView definition = value.definition();
		var generatedDefinition = new de.zorro909.skywright.backend.boundary.generated.model.DatasetDefinition(
				definition.datasetId(), definition.definitionId(), definition.versionLabel(),
				definition.contentFingerprint(), definition.manifestIdentity(),
				definition.createdAt().atOffset(ZoneOffset.UTC));
		return new DatasetCatalogRecord(value.revision(), generatedDefinition,
				value.copies().stream().map(this::copy).toList(), value.leases().stream().map(this::lease).toList(),
				value.caches().stream().map(this::cache).toList(),
				value.operations().stream().map(this::operation).toList());
	}

	private de.zorro909.skywright.backend.boundary.generated.model.DatasetCopy copy(DatasetCopyView value) {
		return new de.zorro909.skywright.backend.boundary.generated.model.DatasetCopy(value.id(),
				value.targetStorageId(), DatasetCopyRole.fromValue(wireValue(value.role())), value.revision(),
				this.generation(value.currentGeneration()),
				value.generationHistory().stream().map(this::generation).toList(), value.activeLeaseCount());
	}

	private de.zorro909.skywright.backend.boundary.generated.model.DatasetCopyGeneration generation(
			DatasetCopyGenerationView value) {
		return new de.zorro909.skywright.backend.boundary.generated.model.DatasetCopyGeneration(value.number(),
				value.location(), value.manifestIdentity(), value.contentFingerprint(), value.verifiedBytes(),
				value.createdAt().atOffset(ZoneOffset.UTC), value.verifiedAt().atOffset(ZoneOffset.UTC),
				value.acceptingLeases(), de.zorro909.skywright.backend.boundary.generated.model.DatasetCopyAvailability
					.fromValue(wireValue(value.availability())));
	}

	private de.zorro909.skywright.backend.boundary.generated.model.DatasetLease lease(DatasetLeaseView value) {
		return new de.zorro909.skywright.backend.boundary.generated.model.DatasetLease(value.id(), value.runRecordId(),
				value.copyId(), value.generation(), value.acquiredAt().atOffset(ZoneOffset.UTC),
				value.endedAt() == null ? null : value.endedAt().atOffset(ZoneOffset.UTC), value.endReason());
	}

	private de.zorro909.skywright.backend.boundary.generated.model.DatasetCache cache(DatasetCacheView value) {
		return new de.zorro909.skywright.backend.boundary.generated.model.DatasetCache(value.id(),
				de.zorro909.skywright.backend.boundary.generated.model.DatasetCacheOwnerType
					.fromValue(wireValue(value.ownerType())),
				value.ownerId(), value.measuredBytes(), value.verifiedAt().atOffset(ZoneOffset.UTC),
				value.lastUsedAt().atOffset(ZoneOffset.UTC), value.createdAt().atOffset(ZoneOffset.UTC));
	}

	private DatasetCopyOperation operation(DatasetCopyOperationView value) {
		return new DatasetCopyOperation(value.id(),
				de.zorro909.skywright.backend.boundary.generated.model.DatasetCopyOperationKind
					.fromValue(wireValue(value.kind())),
				value.copyId(), value.generation(),
				de.zorro909.skywright.backend.boundary.generated.model.DatasetCopyOperationProgress
					.fromValue(wireValue(value.progress())),
				value.attempts(), value.failureCode(), value.failureSummary(), value.retryable(),
				value.startedAt().atOffset(ZoneOffset.UTC), value.updatedAt().atOffset(ZoneOffset.UTC));
	}

	private static UUID cursor(String value) {
		try {
			return UUID.fromString(value);
		}
		catch (IllegalArgumentException failure) {
			throw new DatasetCatalogConflictException("DATASET_CATALOG_CURSOR_INVALID",
					"cursor must be a Dataset Definition identity returned by this operation");
		}
	}

	private static String wireValue(Enum<?> value) {
		return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
	}

}
