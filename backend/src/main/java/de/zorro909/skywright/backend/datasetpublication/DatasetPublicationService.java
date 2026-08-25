package de.zorro909.skywright.backend.datasetpublication;

import de.zorro909.skywright.backend.datasetcatalog.DatasetCatalog;
import de.zorro909.skywright.backend.datasetcatalog.DatasetPublication;
import de.zorro909.skywright.backend.targetstorage.TargetStorageRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DatasetPublicationService implements DatasetPublicationOperations {

	private static final String FORMAT = "mosaicml-streaming-mds@2";

	private final EntityManager entityManager;

	private final TargetStorageRegistry targetStorages;

	private final DatasetCatalog catalog;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	private final DatasetPublicationCommitGate commitGate;

	DatasetPublicationService(EntityManager entityManager, TargetStorageRegistry targetStorages, DatasetCatalog catalog,
			ApplicationEventPublisher events, Clock clock, DatasetPublicationCommitGate commitGate) {
		this.entityManager = entityManager;
		this.targetStorages = targetStorages;
		this.catalog = catalog;
		this.events = events;
		this.clock = clock;
		this.commitGate = commitGate;
	}

	@Transactional
	DatasetPublicationView initiate(DatasetPublicationRequest request) {
		validate(request);
		if (request.datasetId() != null) {
			DatasetLineageEntity lineage = this.entityManager.find(DatasetLineageEntity.class, request.datasetId());
			if (lineage == null) {
				throw new DatasetPublicationException("DATASET_NOT_FOUND", "The requested Dataset does not exist",
						false);
			}
			if (lineage.revision != request.expectedDatasetRevision()) {
				throw new DatasetPublicationException("DATASET_REVISION_STALE",
						"The Dataset revision changed after the preferred-definition decision", false);
			}
		}
		if (!this.targetStorages.eligibleDataset(request.targetStorageId())) {
			throw new DatasetPublicationException("DATASET_TARGET_STORAGE_INELIGIBLE",
					"The selected Target Storage is not eligible for Dataset publication", false);
		}
		String versionLabel = request.versionLabel() == null ? this.fingerprintVersion(request)
				: request.versionLabel();
		DatasetPublicationEntity publication = DatasetPublicationEntity
			.initiate(new DatasetPublicationRequest(request.targetStorageId(), request.datasetId(),
					request.expectedDatasetRevision(), request.preferredDefinitionDecision(), versionLabel,
					request.formatIdentity(), request.manifestIdentity(), request.contentFingerprint(),
					request.objectCount(), request.byteCount()), this.clock.instant());
		publication.requestedVersionLabel = request.versionLabel();
		this.entityManager.persist(publication);
		return publication.view();
	}

	@Transactional(readOnly = true)
	DatasetPublicationView resume(UUID publicationId, DatasetPublicationRequest request) {
		validate(request);
		DatasetPublicationEntity operation = this.publication(publicationId);
		UUID requestedDatasetId = operation.expectedDatasetRevision == null ? null : operation.datasetId;
		if (!java.util.Objects.equals(requestedDatasetId, request.datasetId())
				|| !java.util.Objects.equals(operation.expectedDatasetRevision, request.expectedDatasetRevision())
				|| operation.preferredDefinitionDecision != request.preferredDefinitionDecision()
				|| !java.util.Objects.equals(operation.requestedVersionLabel, request.versionLabel())
				|| !operation.targetStorageId.equals(request.targetStorageId())
				|| !operation.formatIdentity.equals(request.formatIdentity())
				|| !operation.manifestIdentity.equals(request.manifestIdentity())
				|| !operation.contentFingerprint.equals(request.contentFingerprint())
				|| operation.objectCount != request.objectCount() || operation.byteCount != request.byteCount()) {
			throw new DatasetPublicationException("DATASET_PUBLICATION_CONFLICT",
					"The resumed Dataset Publication changes immutable facts", false);
		}
		return operation.view();
	}

	@Transactional
	DatasetPublicationView progress(UUID publicationId, DatasetPublicationProgress progress) {
		DatasetPublicationEntity operation = this.publication(publicationId);
		if (progress.uploadedObjectCount() > operation.objectCount
				|| progress.uploadedByteCount() > operation.byteCount) {
			throw new DatasetPublicationException("DATASET_PUBLICATION_PROGRESS_INVALID",
					"Dataset Publication progress exceeds its accepted bounds", false);
		}
		if (operation.state == DatasetPublicationState.COMMITTED || operation.state == DatasetPublicationState.VERIFYING
				|| operation.state == DatasetPublicationState.COMMITTING
				|| operation.state == DatasetPublicationState.ABORTING
				|| operation.state == DatasetPublicationState.ABORTED
				|| operation.state == DatasetPublicationState.PUBLISHED_CLEANUP_PENDING
				|| operation.state == DatasetPublicationState.FAILED_CLEANUP
				|| operation.state == DatasetPublicationState.FAILED && !operation.retryable) {
			return operation.view();
		}
		operation.state = DatasetPublicationState.UPLOADING;
		operation.uploadedObjectCount = Math.max(operation.uploadedObjectCount, progress.uploadedObjectCount());
		operation.uploadedByteCount = Math.max(operation.uploadedByteCount, progress.uploadedByteCount());
		operation.failureCode = null;
		operation.failureDetail = null;
		operation.unavailableSource = null;
		operation.retryable = false;
		operation.updatedAt = this.clock.instant();
		return operation.view();
	}

	@Transactional
	DatasetPublicationView failLocal(UUID publicationId, DatasetPublicationFailure failure) {
		DatasetPublicationEntity operation = this.publication(publicationId);
		if (operation.state == DatasetPublicationState.VERIFYING || operation.state == DatasetPublicationState.COMMITTED
				|| operation.state == DatasetPublicationState.COMMITTING
				|| operation.state == DatasetPublicationState.ABORTING
				|| operation.state == DatasetPublicationState.ABORTED
				|| operation.state == DatasetPublicationState.PUBLISHED_CLEANUP_PENDING
				|| operation.state == DatasetPublicationState.FAILED_CLEANUP
				|| operation.state == DatasetPublicationState.FAILED && !operation.retryable) {
			return operation.view();
		}
		DatasetPublicationFailureFacts facts = safeFailure(failure.failureCode());
		if (facts == null) {
			throw new DatasetPublicationException("DATASET_PUBLICATION_FAILURE_INVALID",
					"Dataset Publication failure detail is invalid", false);
		}
		operation.state = DatasetPublicationState.FAILED;
		operation.failureCode = failure.failureCode();
		operation.failureDetail = facts.detail();
		operation.unavailableSource = facts.unavailableSource();
		operation.retryable = facts.retryable();
		operation.updatedAt = this.clock.instant();
		operation.completedAt = null;
		return operation.view();
	}

	@Transactional(readOnly = true)
	DatasetPublicationView get(UUID publicationId) {
		return this.publication(publicationId).view();
	}

	@Transactional
	DatasetPublicationView abort(UUID publicationId) {
		DatasetPublicationEntity operation = this.publicationForUpdate(publicationId);
		if (operation.state == DatasetPublicationState.ABORTED || operation.state == DatasetPublicationState.ABORTING) {
			return operation.view();
		}
		if (operation.state == DatasetPublicationState.COMMITTING
				|| operation.state == DatasetPublicationState.PUBLISHED_CLEANUP_PENDING
				|| operation.state == DatasetPublicationState.COMMITTED
				|| operation.state == DatasetPublicationState.FAILED_CLEANUP
						&& operation.preferredDefinitionId != null) {
			throw new DatasetPublicationException("DATASET_PUBLICATION_COMMIT_STARTED",
					"The Dataset Publication crossed its catalog commit boundary", false);
		}
		operation.state = DatasetPublicationState.ABORTING;
		operation.retryable = false;
		operation.failureCode = null;
		operation.failureDetail = null;
		operation.unavailableSource = null;
		operation.completedAt = null;
		operation.updatedAt = this.clock.instant();
		this.events.publishEvent(new DatasetPublicationCleanupRequested(publicationId));
		return operation.view();
	}

	@Transactional
	DatasetPublicationView retryCleanup(UUID publicationId) {
		DatasetPublicationEntity operation = this.publicationForUpdate(publicationId);
		if (operation.state == DatasetPublicationState.ABORTING
				|| operation.state == DatasetPublicationState.PUBLISHED_CLEANUP_PENDING
				|| operation.state == DatasetPublicationState.ABORTED
				|| operation.state == DatasetPublicationState.COMMITTED) {
			return operation.view();
		}
		if (operation.state != DatasetPublicationState.FAILED_CLEANUP) {
			throw new DatasetPublicationException("DATASET_PUBLICATION_CLEANUP_CONFLICT",
					"The Dataset Publication has no retryable cleanup", false);
		}
		operation.state = operation.preferredDefinitionId == null ? DatasetPublicationState.ABORTING
				: DatasetPublicationState.PUBLISHED_CLEANUP_PENDING;
		operation.retryable = false;
		operation.failureCode = null;
		operation.failureDetail = null;
		operation.unavailableSource = null;
		operation.completedAt = operation.preferredDefinitionId == null ? null : operation.completedAt;
		operation.updatedAt = this.clock.instant();
		this.events.publishEvent(new DatasetPublicationCleanupRequested(publicationId));
		return operation.view();
	}

	@Transactional
	DatasetPublicationView complete(UUID publicationId) {
		DatasetPublicationEntity operation = this.publication(publicationId);
		if (operation.state == DatasetPublicationState.COMMITTED || operation.state == DatasetPublicationState.VERIFYING
				|| operation.state == DatasetPublicationState.PUBLISHED_CLEANUP_PENDING
				|| operation.state == DatasetPublicationState.ABORTING
				|| operation.state == DatasetPublicationState.ABORTED
				|| operation.state == DatasetPublicationState.FAILED_CLEANUP
				|| operation.state == DatasetPublicationState.FAILED && !operation.retryable) {
			return operation.view();
		}
		if (!this.targetStorages.eligibleDataset(operation.targetStorageId)) {
			throw new DatasetPublicationException("DATASET_TARGET_STORAGE_INELIGIBLE",
					"The selected Target Storage is not eligible for Dataset publication", false);
		}
		operation.state = DatasetPublicationState.VERIFYING;
		operation.failureCode = null;
		operation.failureDetail = null;
		operation.unavailableSource = null;
		operation.retryable = false;
		operation.completedAt = null;
		operation.verificationWorkerPid = 0;
		operation.updatedAt = this.clock.instant();
		this.events.publishEvent(new DatasetPublicationVerificationRequested(publicationId));
		return operation.view();
	}

	@Transactional(readOnly = true)
	public DatasetPublicationView verificationInput(UUID publicationId) {
		DatasetPublicationEntity operation = this.publication(publicationId);
		return operation.state == DatasetPublicationState.VERIFYING ? operation.view() : null;
	}

	@Transactional(readOnly = true)
	public List<UUID> pendingVerifications() {
		return this.entityManager
			.createQuery("select publicationId from DatasetPublicationEntity where state = :state", UUID.class)
			.setParameter("state", DatasetPublicationState.VERIFYING)
			.getResultList();
	}

	@Transactional(readOnly = true)
	public DatasetPublicationView cleanupInput(UUID publicationId) {
		DatasetPublicationEntity operation = this.publication(publicationId);
		return operation.state == DatasetPublicationState.ABORTING
				|| operation.state == DatasetPublicationState.PUBLISHED_CLEANUP_PENDING ? operation.view() : null;
	}

	@Transactional(readOnly = true)
	public List<UUID> pendingCleanups() {
		return this.entityManager
			.createQuery("select publicationId from DatasetPublicationEntity where state in :states", UUID.class)
			.setParameter("states",
					List.of(DatasetPublicationState.ABORTING, DatasetPublicationState.PUBLISHED_CLEANUP_PENDING))
			.getResultList();
	}

	@Transactional
	public void commit(UUID publicationId, DatasetPublicationWorkerResult verified) {
		DatasetPublicationEntity operation = this.publicationForUpdate(publicationId);
		if (operation.state != DatasetPublicationState.VERIFYING) {
			return;
		}
		operation.state = DatasetPublicationState.COMMITTING;
		if (operation.expectedDatasetRevision != null) {
			this.commitGate.await(operation.datasetId);
		}
		DatasetLineageEntity lineage = operation.expectedDatasetRevision == null ? null : this.entityManager
			.find(DatasetLineageEntity.class, operation.datasetId, LockModeType.PESSIMISTIC_WRITE);
		if (operation.expectedDatasetRevision != null && lineage == null) {
			throw new DatasetPublicationException("DATASET_NOT_FOUND", "The requested Dataset does not exist", false);
		}
		if (lineage != null) {
			lineage.publish(operation.definitionId, operation.expectedDatasetRevision,
					operation.preferredDefinitionDecision);
		}
		this.catalog.publish(new DatasetPublication(operation.datasetId, operation.definitionId, operation.versionLabel,
				operation.formatIdentity, operation.contentFingerprint, operation.manifestIdentity, operation.copyId,
				operation.targetStorageId, operation.payloadLocation, verified.byteCount(), verified.verifiedAt(),
				verified.manifest()));
		if (lineage == null) {
			lineage = new DatasetLineageEntity(operation.datasetId, operation.definitionId, operation.createdAt);
			this.entityManager.persist(lineage);
		}
		operation.state = DatasetPublicationState.PUBLISHED_CLEANUP_PENDING;
		operation.verifiedObjectCount = verified.objectCount();
		operation.verifiedByteCount = verified.byteCount();
		operation.preferredDefinitionId = lineage.preferredDefinitionId;
		operation.preferredDefinitionChanged = operation.preferredDefinitionId.equals(operation.definitionId);
		operation.retryable = false;
		operation.failureCode = null;
		operation.failureDetail = null;
		operation.unavailableSource = null;
		operation.verifiedAt = verified.verifiedAt();
		operation.completedAt = this.clock.instant();
		operation.updatedAt = operation.completedAt;
		operation.verificationWorkerPid = verified.workerPid();
		this.events.publishEvent(new DatasetPublicationCleanupRequested(publicationId));
	}

	@Transactional
	public void cleanupSucceeded(UUID publicationId, DatasetPublicationWorkerResult result) {
		DatasetPublicationEntity operation = this.publicationForUpdate(publicationId);
		if (operation.state == DatasetPublicationState.ABORTING) {
			operation.state = DatasetPublicationState.ABORTED;
			operation.completedAt = this.clock.instant();
		}
		else if (operation.state == DatasetPublicationState.PUBLISHED_CLEANUP_PENDING) {
			operation.state = DatasetPublicationState.COMMITTED;
		}
		else {
			return;
		}
		operation.retryable = false;
		operation.failureCode = null;
		operation.failureDetail = null;
		operation.unavailableSource = null;
		operation.updatedAt = this.clock.instant();
	}

	@Transactional
	public void cleanupFailed(UUID publicationId, DatasetPublicationWorkerResult failure) {
		DatasetPublicationEntity operation = this.publicationForUpdate(publicationId);
		if (operation.state != DatasetPublicationState.ABORTING
				&& operation.state != DatasetPublicationState.PUBLISHED_CLEANUP_PENDING) {
			return;
		}
		operation.state = DatasetPublicationState.FAILED_CLEANUP;
		operation.failureCode = failure.failureCode() == null ? "DATASET_CLEANUP_UNAVAILABLE" : failure.failureCode();
		operation.failureDetail = "Dataset Publication cleanup could not verify object and multipart absence";
		operation.unavailableSource = "Dataset Target Storage";
		operation.retryable = true;
		operation.updatedAt = this.clock.instant();
	}

	@Transactional
	public void fail(UUID publicationId, DatasetPublicationWorkerResult failure) {
		DatasetPublicationEntity operation = this.publication(publicationId);
		if (operation.state != DatasetPublicationState.VERIFYING) {
			return;
		}
		operation.state = DatasetPublicationState.FAILED;
		operation.failureCode = failure.failureCode();
		operation.failureDetail = failureDetail(failure);
		operation.unavailableSource = unavailableSource(failure);
		operation.retryable = failure.retryable();
		operation.verificationWorkerPid = failure.workerPid();
		operation.completedAt = this.clock.instant();
		operation.updatedAt = operation.completedAt;
	}

	static String failureDetail(DatasetPublicationWorkerResult failure) {
		if ("DATASET_PROJECTION_UNAVAILABLE".equals(failure.failureCode())) {
			return "Managed credential projection is temporarily unavailable";
		}
		if ("DATASET_TARGET_STORAGE_INELIGIBLE".equals(failure.failureCode())) {
			return "The selected Dataset Target Storage is no longer eligible";
		}
		if ("DATASET_VERIFICATION_PROCESS_UNAVAILABLE".equals(failure.failureCode())) {
			return "The independent Dataset verification worker is temporarily unavailable";
		}
		if ("DATASET_DATABASE_UNAVAILABLE".equals(failure.failureCode())) {
			return "The Dataset Publication database is temporarily unavailable";
		}
		return failure.retryable() ? "Independent Dataset verification is temporarily unavailable"
				: "Independent Dataset verification rejected the staged content";
	}

	static String unavailableSource(DatasetPublicationWorkerResult failure) {
		if ("DATASET_PROJECTION_UNAVAILABLE".equals(failure.failureCode())) {
			return "Managed Credential Projection";
		}
		if ("DATASET_VERIFICATION_PROCESS_UNAVAILABLE".equals(failure.failureCode())) {
			return "Dataset Verification Worker";
		}
		if ("DATASET_DATABASE_UNAVAILABLE".equals(failure.failureCode())) {
			return "Publication Database";
		}
		return failure.retryable() ? "Dataset Target Storage" : null;
	}

	@Transactional(readOnly = true)
	DatasetLineageView dataset(UUID datasetId) {
		DatasetLineageEntity lineage = this.entityManager.find(DatasetLineageEntity.class, datasetId);
		if (lineage == null) {
			throw new DatasetPublicationException("DATASET_NOT_FOUND", "The requested Dataset does not exist", false);
		}
		return lineage.view();
	}

	private DatasetPublicationEntity publication(UUID publicationId) {
		DatasetPublicationEntity publication = this.entityManager.find(DatasetPublicationEntity.class, publicationId);
		if (publication == null) {
			throw new DatasetPublicationException("DATASET_PUBLICATION_NOT_FOUND",
					"The requested Dataset Publication does not exist", false);
		}
		return publication;
	}

	private DatasetPublicationEntity publicationForUpdate(UUID publicationId) {
		DatasetPublicationEntity publication = this.entityManager.find(DatasetPublicationEntity.class, publicationId,
				LockModeType.PESSIMISTIC_WRITE);
		if (publication == null) {
			throw new DatasetPublicationException("DATASET_PUBLICATION_NOT_FOUND",
					"The requested Dataset Publication does not exist", false);
		}
		return publication;
	}

	private static void validate(DatasetPublicationRequest request) {
		boolean fresh = request.datasetId() == null && request.expectedDatasetRevision() == null
				&& request.preferredDefinitionDecision() == null;
		boolean existing = request.datasetId() != null && request.expectedDatasetRevision() != null
				&& request.expectedDatasetRevision() > 0 && request.preferredDefinitionDecision() != null;
		if ((!fresh && !existing) || request.targetStorageId() == null
				|| request.versionLabel() != null
						&& (request.versionLabel().isBlank() || request.versionLabel().length() > 255)
				|| !FORMAT.equals(request.formatIdentity()) || !digest(request.manifestIdentity())
				|| !digest(request.contentFingerprint()) || request.objectCount() < 1 || request.byteCount() < 0) {
			throw new DatasetPublicationException("DATASET_PUBLICATION_INVALID",
					"The Dataset Publication request is invalid", false);
		}
	}

	private String fingerprintVersion(DatasetPublicationRequest request) {
		String fingerprint = request.contentFingerprint().substring("sha256:".length());
		if (request.datasetId() == null) {
			return fingerprint.substring(0, 16);
		}
		for (int length = 16; length <= fingerprint.length(); length += 2) {
			String candidate = fingerprint.substring(0, length);
			if (!this.catalog.hasVersionLabel(request.datasetId(), candidate)) {
				return candidate;
			}
		}
		throw new DatasetPublicationException("DATASET_FINGERPRINT_CONFLICT",
				"The Dataset already contains a conflicting full content fingerprint", false);
	}

	private static DatasetPublicationFailureFacts safeFailure(String code) {
		return switch (code == null ? "" : code) {
			case "DATASET_UPLOAD_FAILED" -> new DatasetPublicationFailureFacts(
					"Direct Dataset upload is temporarily unavailable", "Dataset Target Storage", true);
			case "DATASET_LOCAL_CREDENTIAL_UNAVAILABLE" -> new DatasetPublicationFailureFacts(
					"Local object-storage credentials are unavailable", "Local credential provider", true);
			case "DATASET_SOURCE_MUTATED" -> new DatasetPublicationFailureFacts(
					"The local Dataset corpus changed after its identity was accepted", null, false);
			case "DATASET_UPLOAD_CONFLICT" -> new DatasetPublicationFailureFacts(
					"An allocated Dataset object conflicts with the accepted manifest", null, false);
			case "DATASET_COMMIT_RESPONSE_AMBIGUOUS" -> new DatasetPublicationFailureFacts(
					"The completion request did not commit and can be retried", "Control plane", true);
			default -> null;
		};
	}

	private record DatasetPublicationFailureFacts(String detail, String unavailableSource, boolean retryable) {
	}

	private static boolean digest(String value) {
		return value != null && value.matches("sha256:[0-9a-f]{64}");
	}

}
