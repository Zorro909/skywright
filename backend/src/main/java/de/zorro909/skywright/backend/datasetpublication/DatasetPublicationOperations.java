package de.zorro909.skywright.backend.datasetpublication;

import java.util.List;
import java.util.UUID;

interface DatasetPublicationOperations {

	DatasetPublicationView verificationInput(UUID publicationId);

	List<UUID> pendingVerifications();

	default DatasetPublicationView cleanupInput(UUID publicationId) {
		return null;
	}

	default List<UUID> pendingCleanups() {
		return List.of();
	}

	void commit(UUID publicationId, DatasetPublicationWorkerResult verified);

	void fail(UUID publicationId, DatasetPublicationWorkerResult failure);

	default void cleanupSucceeded(UUID publicationId, DatasetPublicationWorkerResult result) {
		throw new UnsupportedOperationException();
	}

	default void cleanupFailed(UUID publicationId, DatasetPublicationWorkerResult failure) {
		throw new UnsupportedOperationException();
	}

}
