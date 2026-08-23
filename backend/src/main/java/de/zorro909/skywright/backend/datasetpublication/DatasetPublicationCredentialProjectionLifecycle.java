package de.zorro909.skywright.backend.datasetpublication;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface DatasetPublicationCredentialProjectionLifecycle {

	UUID projected(UUID publicationId, UUID bindingId, long bindingRevision);

	void prepared(UUID projectionId, Path jobDirectory);

	void launched(UUID projectionId, long workerPid, Instant workerStartedAt);

	List<DatasetPublicationOpenCredentialProjection> open();

	void released(UUID projectionId);

}
