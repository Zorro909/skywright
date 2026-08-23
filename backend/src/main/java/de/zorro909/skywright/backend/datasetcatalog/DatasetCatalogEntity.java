package de.zorro909.skywright.backend.datasetcatalog;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity(name = "DatasetCatalogEntity")
@Table(name = "dataset_catalog")
class DatasetCatalogEntity {

	@Id
	@Column(name = "definition_id")
	UUID definitionId;

	@Column(name = "dataset_id", nullable = false)
	UUID datasetId;

	@Column(name = "version_label")
	String versionLabel;

	@Column(name = "format_identity", nullable = false)
	String formatIdentity;

	@Column(name = "content_fingerprint", nullable = false)
	String contentFingerprint;

	@Column(name = "manifest_identity", nullable = false)
	String manifestIdentity;

	@Column(name = "created_at", nullable = false)
	Instant createdAt;

	@Column(nullable = false)
	long revision;

	@Version
	@Column(name = "persistence_version", nullable = false)
	long persistenceVersion;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "dataset_copy", joinColumns = @JoinColumn(name = "definition_id"))
	@OrderColumn(name = "copy_position")
	List<DatasetCopyEmbeddable> copies = new ArrayList<>();

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "dataset_copy_generation", joinColumns = @JoinColumn(name = "definition_id"))
	@OrderColumn(name = "generation_position")
	List<DatasetGenerationEmbeddable> generations = new ArrayList<>();

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "dataset_manifest_entry", joinColumns = @JoinColumn(name = "definition_id"))
	@OrderColumn(name = "manifest_position")
	List<DatasetManifestEntryEmbeddable> manifest = new ArrayList<>();

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "dataset_lease", joinColumns = @JoinColumn(name = "definition_id"))
	@OrderColumn(name = "lease_position")
	List<DatasetLeaseEmbeddable> leases = new ArrayList<>();

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "dataset_copy_operation", joinColumns = @JoinColumn(name = "definition_id"))
	@OrderColumn(name = "operation_position")
	List<DatasetCopyOperationEmbeddable> operations = new ArrayList<>();

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "dataset_cache", joinColumns = @JoinColumn(name = "definition_id"))
	@OrderColumn(name = "cache_position")
	List<DatasetCacheEmbeddable> caches = new ArrayList<>();

	protected DatasetCatalogEntity() {
	}

	static DatasetCatalogEntity from(DatasetCatalogSnapshot value) {
		DatasetCatalogEntity result = new DatasetCatalogEntity();
		result.apply(value);
		return result;
	}

	void apply(DatasetCatalogSnapshot value) {
		this.definitionId = value.definition().definitionId();
		this.datasetId = value.definition().datasetId();
		this.versionLabel = value.definition().versionLabel();
		this.formatIdentity = value.definition().formatIdentity();
		this.contentFingerprint = value.definition().contentFingerprint();
		this.manifestIdentity = value.definition().manifestIdentity();
		this.createdAt = value.definition().createdAt();
		this.revision = value.revision();
		this.copies = value.copies().stream().map(DatasetCopyEmbeddable::from).toList();
		this.generations = value.copies()
			.stream()
			.flatMap(copy -> copy.generationHistory()
				.stream()
				.map(generation -> DatasetGenerationEmbeddable.from(copy.id(), generation)))
			.toList();
		this.manifest = value.manifest().stream().map(DatasetManifestEntryEmbeddable::from).toList();
		this.leases = value.leases().stream().map(DatasetLeaseEmbeddable::from).toList();
		this.operations = value.operations().stream().map(DatasetCopyOperationEmbeddable::from).toList();
		this.caches = value.caches().stream().map(DatasetCacheEmbeddable::from).toList();
	}

	DatasetCatalogAggregate domain() {
		DatasetDefinitionView definition = new DatasetDefinitionView(this.datasetId, this.definitionId,
				this.versionLabel, this.formatIdentity, this.contentFingerprint, this.manifestIdentity, this.createdAt);
		List<DatasetCopyView> decodedCopies = this.copies.stream().map(copy -> {
			List<DatasetCopyGenerationView> history = this.generations.stream()
				.filter(generation -> generation.copyId.equals(copy.id))
				.map(DatasetGenerationEmbeddable::domain)
				.toList();
			DatasetCopyGenerationView current = history.stream()
				.filter(generation -> generation.number() == copy.currentGeneration)
				.findFirst()
				.orElseThrow();
			return new DatasetCopyView(copy.id, copy.targetStorageId, copy.role, copy.revision, current, history,
					copy.activeLeaseCount);
		}).toList();
		return DatasetCatalogAggregate.restore(this.revision, definition, decodedCopies,
				this.manifest.stream().map(DatasetManifestEntryEmbeddable::domain).toList(),
				this.leases.stream().map(lease -> lease.domain(this.definitionId)).toList(),
				this.caches.stream().map(DatasetCacheEmbeddable::domain).toList(),
				this.operations.stream().map(DatasetCopyOperationEmbeddable::domain).toList());
	}

}
