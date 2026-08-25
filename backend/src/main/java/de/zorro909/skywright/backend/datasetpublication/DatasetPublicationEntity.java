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

	@Column(name = "expected_dataset_revision")
	Long expectedDatasetRevision;

	@Enumerated(EnumType.STRING)
	@Column(name = "preferred_definition_decision")
	PreferredDefinitionDecision preferredDefinitionDecision;

	@Column(name = "requested_version_label")
	String requestedVersionLabel;

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

	@Column(name = "uploaded_object_count", nullable = false)
	long uploadedObjectCount;

	@Column(name = "uploaded_byte_count", nullable = false)
	long uploadedByteCount;

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

	@Column(name = "failure_detail")
	String failureDetail;

	@Column(name = "unavailable_source")
	String unavailableSource;

	@Column(name = "created_at", nullable = false)
	Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	Instant updatedAt;

	@Column(name = "verified_at")
	Instant verifiedAt;

	@Column(name = "completed_at")
	Instant completedAt;

	@Column(name = "verification_worker_pid", nullable = false)
	long verificationWorkerPid;

	@Version
	@Column(name = "persistence_version", nullable = false)
	long persistenceVersion;

	protected DatasetPublicationEntity() {
	}

	static DatasetPublicationEntity initiate(DatasetPublicationRequest request, Instant now) {
		var entity = new DatasetPublicationEntity();
		entity.publicationId = UUID.randomUUID();
		entity.state = DatasetPublicationState.AWAITING_UPLOAD;
		entity.datasetId = request.datasetId() == null ? UUID.randomUUID() : request.datasetId();
		entity.definitionId = UUID.randomUUID();
		entity.copyId = UUID.randomUUID();
		entity.targetStorageId = request.targetStorageId();
		entity.expectedDatasetRevision = request.expectedDatasetRevision();
		entity.preferredDefinitionDecision = request.preferredDefinitionDecision();
		entity.requestedVersionLabel = request.versionLabel();
		entity.versionLabel = request.versionLabel();
		entity.formatIdentity = request.formatIdentity();
		entity.manifestIdentity = request.manifestIdentity();
		entity.contentFingerprint = request.contentFingerprint();
		entity.objectCount = request.objectCount();
		entity.byteCount = request.byteCount();
		entity.payloadLocation = "datasets/" + entity.datasetId + "/" + entity.definitionId;
		entity.operationLocation = "operations/dataset-publications/" + entity.publicationId;
		entity.createdAt = now;
		entity.updatedAt = now;
		return entity;
	}

	DatasetPublicationView view() {
		return new DatasetPublicationView(this.publicationId, this.state, this.datasetId, this.definitionId,
				this.copyId, this.targetStorageId, this.expectedDatasetRevision, this.preferredDefinitionDecision,
				this.versionLabel, this.formatIdentity, this.manifestIdentity, this.contentFingerprint,
				this.objectCount, this.byteCount, this.payloadLocation, this.operationLocation,
				this.uploadedObjectCount, this.uploadedByteCount, this.verifiedObjectCount, this.verifiedByteCount,
				this.preferredDefinitionId, this.preferredDefinitionChanged, this.retryable, this.failureCode,
				this.failureDetail, this.unavailableSource, retryGuidance(), this.createdAt, this.updatedAt,
				this.verifiedAt, this.completedAt, this.verificationWorkerPid);
	}

	private String retryGuidance() {
		if (this.state == DatasetPublicationState.COMMITTED) {
			return "The publication is committed; inspect this operation for its original result.";
		}
		if (this.state == DatasetPublicationState.VERIFYING || this.state == DatasetPublicationState.COMMITTING) {
			return "Wait for managed verification and inspect this operation again.";
		}
		if (this.state == DatasetPublicationState.FAILED && !this.retryable) {
			return switch (this.failureCode == null ? "" : this.failureCode) {
				case "DATASET_SOURCE_MUTATED" ->
					"Repair the local Dataset corpus and start a new publication; this operation cannot be resumed.";
				case "DATASET_UPLOAD_CONFLICT" ->
					"Reconcile the conflicting allocated object and start a new publication; this operation cannot be resumed.";
				default -> "Inspect the failure and start a new publication; this operation cannot be resumed.";
			};
		}
		return "Resume with --resume " + this.publicationId + " and the original immutable publication facts.";
	}

}
