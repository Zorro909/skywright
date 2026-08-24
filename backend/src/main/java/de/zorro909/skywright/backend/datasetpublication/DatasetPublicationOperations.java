package de.zorro909.skywright.backend.datasetpublication;

import java.util.List;
import java.util.UUID;

interface DatasetPublicationOperations {

	DatasetPublicationView verificationInput(UUID publicationId);

	List<UUID> pendingVerifications();

	void commit(UUID publicationId, DatasetPublicationWorkerResult verified);

	void fail(UUID publicationId, DatasetPublicationWorkerResult failure);

}
