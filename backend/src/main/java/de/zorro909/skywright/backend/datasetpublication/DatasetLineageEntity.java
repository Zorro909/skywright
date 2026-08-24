package de.zorro909.skywright.backend.datasetpublication;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity(name = "DatasetLineageEntity")
@Table(name = "dataset_lineage")
class DatasetLineageEntity {

	@Id
	@Column(name = "dataset_id")
	UUID datasetId;

	@Column(nullable = false)
	long revision;

	@Column(name = "preferred_definition_id", nullable = false)
	UUID preferredDefinitionId;

	@Column(name = "created_at", nullable = false)
	Instant createdAt;

	@Version
	@Column(name = "persistence_version", nullable = false)
	long persistenceVersion;

	protected DatasetLineageEntity() {
	}

	DatasetLineageEntity(UUID datasetId, UUID preferredDefinitionId, Instant createdAt) {
		this.datasetId = datasetId;
		this.revision = 1;
		this.preferredDefinitionId = preferredDefinitionId;
		this.createdAt = createdAt;
	}

	DatasetLineageView view() {
		return new DatasetLineageView(this.datasetId, this.revision, this.preferredDefinitionId, this.createdAt);
	}

	void publish(UUID definitionId, long expectedRevision, PreferredDefinitionDecision decision) {
		if (this.revision != expectedRevision) {
			throw new DatasetPublicationException("DATASET_REVISION_STALE",
					"The Dataset revision changed after the preferred-definition decision", false);
		}
		if (decision == PreferredDefinitionDecision.ADVANCE) {
			this.preferredDefinitionId = definitionId;
		}
		this.revision++;
	}

}
