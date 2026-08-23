package de.zorro909.skywright.backend.datasetpublication;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity(name = "DatasetPublicationEntity")
@Table(name = "dataset_publication")
class DatasetPublicationEntity {

	@Id
	@Column(name = "publication_id")
	UUID publicationId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	DatasetPublicationState state;

	@Column(name = "dataset_id", nullable = false)
	UUID datasetId;

	@Column(name = "definition_id", nullable = false)
	UUID definitionId;

	@Column(name = "copy_id", nullable = false)
	UUID copyId;

	@Column(name = "target_storage_id", nullable = false)
	UUID targetStorageId;

	@Column(name = "version_label", nullable = false)
	String versionLabel;

	@Column(name = "format_identity", nullable = false)
	String formatIdentity;

	@Column(name = "manifest_identity", nullable = false)
	String manifestIdentity;

	@Column(name = "content_fingerprint", nullable = false)
	String contentFingerprint;

	@Column(name = "object_count", nullable = false)
	long objectCount;

	@Column(name = "byte_count", nullable = false)
	long byteCount;

	@Column(name = "payload_location", nullable = false)
	String payloadLocation;

	@Column(name = "operation_location", nullable = false)
	String operationLocation;

	@Column(name = "verified_object_count", nullable = false)
	long verifiedObjectCount;

	@Column(name = "verified_byte_count", nullable = false)
	long verifiedByteCount;

	@Column(name = "preferred_definition_id")
	UUID preferredDefinitionId;

	@Column(name = "preferred_definition_changed", nullable = false)
	boolean preferredDefinitionChanged;

	@Column(nullable = false)
	boolean retryable;

	@Column(name = "failure_code")
	String failureCode;

	@Column(name = "created_at", nullable = false)
	Instant createdAt;

	@Column(name = "verified_at")
	Instant verifiedAt;

	@Column(name = "completed_at")
	Instant completedAt;

	@Version
	@Column(name = "persistence_version", nullable = false)
	long persistenceVersion;

	protected DatasetPublicationEntity() {
	}

	static DatasetPublicationEntity initiate(DatasetPublicationRequest request, Instant now) {
		var entity = new DatasetPublicationEntity();
		entity.publicationId = UUID.randomUUID();
		entity.state = DatasetPublicationState.AWAITING_UPLOAD;
		entity.datasetId = UUID.randomUUID();
		entity.definitionId = UUID.randomUUID();
		entity.copyId = UUID.randomUUID();
		entity.targetStorageId = request.targetStorageId();
		entity.versionLabel = request.versionLabel();
		entity.formatIdentity = request.formatIdentity();
		entity.manifestIdentity = request.manifestIdentity();
		entity.contentFingerprint = request.contentFingerprint();
		entity.objectCount = request.objectCount();
		entity.byteCount = request.byteCount();
		entity.payloadLocation = "datasets/" + entity.datasetId + "/" + entity.definitionId;
		entity.operationLocation = "operations/dataset-publications/" + entity.publicationId;
		entity.createdAt = now;
		return entity;
	}

	DatasetPublicationView view() {
		return new DatasetPublicationView(this.publicationId, this.state, this.datasetId, this.definitionId,
				this.copyId, this.targetStorageId, this.versionLabel, this.formatIdentity, this.manifestIdentity,
				this.contentFingerprint, this.objectCount, this.byteCount, this.payloadLocation, this.operationLocation,
				this.verifiedObjectCount, this.verifiedByteCount, this.preferredDefinitionId,
				this.preferredDefinitionChanged, this.retryable, this.failureCode, this.createdAt, this.verifiedAt,
				this.completedAt);
	}

}
