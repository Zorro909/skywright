package de.zorro909.skywright.backend.datasetpublication;

import jakarta.persistence.EntityManager;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DatasetPublicationCredentialProjections implements DatasetPublicationCredentialProjectionLifecycle {

	private final EntityManager entityManager;

	private final Clock clock;

	DatasetPublicationCredentialProjections(EntityManager entityManager, Clock clock) {
		this.entityManager = entityManager;
		this.clock = clock;
	}

	@Transactional
	@Override
	public UUID projected(UUID publicationId, UUID bindingId, long bindingRevision) {
		var record = new DatasetPublicationCredentialProjectionEntity(publicationId, bindingId, bindingRevision,
				this.clock.instant());
		this.entityManager.persist(record);
		return record.projectionId;
	}

	@Transactional
	@Override
	public void prepared(UUID projectionId, Path jobDirectory) {
		require(projectionId).jobDirectory = jobDirectory.toString();
	}

	@Transactional
	@Override
	public void launched(UUID projectionId, long workerPid, Instant workerStartedAt) {
		var record = require(projectionId);
		record.workerPid = workerPid;
		record.workerStartedAt = workerStartedAt;
	}

	@Transactional(readOnly = true)
	@Override
	public List<DatasetPublicationOpenCredentialProjection> open() {
		return this.entityManager
			.createQuery("select projection from DatasetPublicationCredentialProjectionEntity projection "
					+ "where projection.releasedAt is null", DatasetPublicationCredentialProjectionEntity.class)
			.getResultStream()
			.map(projection -> new DatasetPublicationOpenCredentialProjection(projection.projectionId,
					projection.workerPid, projection.workerStartedAt,
					projection.jobDirectory == null ? null : Path.of(projection.jobDirectory)))
			.toList();
	}

	@Transactional
	@Override
	public void released(UUID projectionId) {
		var record = this.entityManager.find(DatasetPublicationCredentialProjectionEntity.class, projectionId);
		if (record != null && record.releasedAt == null) {
			record.releasedAt = this.clock.instant();
		}
	}

	private DatasetPublicationCredentialProjectionEntity require(UUID projectionId) {
		var record = this.entityManager.find(DatasetPublicationCredentialProjectionEntity.class, projectionId);
		if (record == null) {
			throw new IllegalStateException("Credential Projection Record is unavailable");
		}
		return record;
	}

}
