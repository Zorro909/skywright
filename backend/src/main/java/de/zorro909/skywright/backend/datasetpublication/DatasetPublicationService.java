package de.zorro909.skywright.backend.datasetpublication;

import de.zorro909.skywright.backend.datasetcatalog.DatasetCatalog;
import de.zorro909.skywright.backend.datasetcatalog.DatasetPublication;
import de.zorro909.skywright.backend.targetstorage.TargetStorageRegistry;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DatasetPublicationService {

	private static final String FORMAT = "mosaicml-streaming-mds@2";

	private final EntityManager entityManager;

	private final TargetStorageRegistry targetStorages;

	private final DatasetCatalog catalog;

	private final DatasetPublicationVerifier verifier;

	private final Clock clock;

	DatasetPublicationService(EntityManager entityManager, TargetStorageRegistry targetStorages, DatasetCatalog catalog,
			DatasetPublicationVerifier verifier, Clock clock) {
		this.entityManager = entityManager;
		this.targetStorages = targetStorages;
		this.catalog = catalog;
		this.verifier = verifier;
		this.clock = clock;
	}

	@Transactional
	DatasetPublicationView initiate(DatasetPublicationRequest request) {
		validate(request);
		if (!this.targetStorages.eligibleDataset(request.targetStorageId())) {
			throw new DatasetPublicationException("DATASET_TARGET_STORAGE_INELIGIBLE",
					"The selected Target Storage is not eligible for Dataset publication", false);
		}
		DatasetPublicationEntity publication = DatasetPublicationEntity.initiate(request, this.clock.instant());
		this.entityManager.persist(publication);
		return publication.view();
	}

	@Transactional(readOnly = true)
	DatasetPublicationView get(UUID publicationId) {
		return this.publication(publicationId).view();
	}

	@Transactional(noRollbackFor = DatasetPublicationException.class)
	DatasetPublicationView complete(UUID publicationId) {
		DatasetPublicationEntity operation = this.publication(publicationId);
		if (operation.state == DatasetPublicationState.COMMITTED) {
			return operation.view();
		}
		if (!this.targetStorages.eligibleDataset(operation.targetStorageId)) {
			throw new DatasetPublicationException("DATASET_TARGET_STORAGE_INELIGIBLE",
					"The selected Target Storage is not eligible for Dataset publication", false);
		}
		VerifiedPublication verified;
		try {
			verified = this.verifier.verify(operation.view());
		}
		catch (DatasetPublicationException failure) {
			operation.state = DatasetPublicationState.FAILED;
			operation.failureCode = failure.errorCode();
			operation.retryable = failure.retryable();
			throw failure;
		}
		this.catalog.publish(new DatasetPublication(operation.datasetId, operation.definitionId, operation.versionLabel,
				operation.formatIdentity, operation.contentFingerprint, operation.manifestIdentity, operation.copyId,
				operation.targetStorageId, operation.payloadLocation, verified.byteCount(), verified.verifiedAt(),
				verified.manifest()));
		DatasetLineageEntity lineage = new DatasetLineageEntity(operation.datasetId, operation.definitionId,
				operation.createdAt);
		this.entityManager.persist(lineage);
		operation.state = DatasetPublicationState.COMMITTED;
		operation.verifiedObjectCount = verified.objectCount();
		operation.verifiedByteCount = verified.byteCount();
		operation.preferredDefinitionId = operation.definitionId;
		operation.preferredDefinitionChanged = true;
		operation.retryable = false;
		operation.failureCode = null;
		operation.verifiedAt = verified.verifiedAt();
		operation.completedAt = this.clock.instant();
		return operation.view();
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

	private static void validate(DatasetPublicationRequest request) {
		if (request.targetStorageId() == null || request.versionLabel() == null || request.versionLabel().isBlank()
				|| request.versionLabel().length() > 255 || !FORMAT.equals(request.formatIdentity())
				|| !digest(request.manifestIdentity()) || !digest(request.contentFingerprint())
				|| request.objectCount() < 1 || request.byteCount() < 0) {
			throw new DatasetPublicationException("DATASET_PUBLICATION_INVALID",
					"The Dataset Publication request is invalid", false);
		}
	}

	private static boolean digest(String value) {
		return value != null && value.matches("sha256:[0-9a-f]{64}");
	}

}
