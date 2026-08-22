package de.zorro909.skywright.backend.datasetcatalog;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.util.UUID;

@Embeddable
class DatasetCopyEmbeddable {

	@Column(name = "copy_id", nullable = false)
	UUID id;

	@Column(name = "target_storage_id", nullable = false)
	UUID targetStorageId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	DatasetCopyRole role;

	@Column(nullable = false)
	long revision;

	@Column(name = "current_generation", nullable = false)
	long currentGeneration;

	@Column(name = "active_lease_count", nullable = false)
	long activeLeaseCount;

	protected DatasetCopyEmbeddable() {
	}

	static DatasetCopyEmbeddable from(DatasetCopyView value) {
		DatasetCopyEmbeddable result = new DatasetCopyEmbeddable();
		result.id = value.id();
		result.targetStorageId = value.targetStorageId();
		result.role = value.role();
		result.revision = value.revision();
		result.currentGeneration = value.currentGeneration().number();
		result.activeLeaseCount = value.activeLeaseCount();
		return result;
	}

}
