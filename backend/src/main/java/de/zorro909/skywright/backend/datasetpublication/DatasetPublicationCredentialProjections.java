package de.zorro909.skywright.backend.datasetpublication;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DatasetPublicationCredentialProjections {

	private final EntityManager entityManager;

	private final Clock clock;

	DatasetPublicationCredentialProjections(EntityManager entityManager, Clock clock) {
		this.entityManager = entityManager;
		this.clock = clock;
	}

	@Transactional
	UUID projected(UUID publicationId, UUID bindingId, long bindingRevision) {
		var record = new DatasetPublicationCredentialProjectionEntity(publicationId, bindingId, bindingRevision,
				this.clock.instant());
		this.entityManager.persist(record);
		return record.projectionId;
	}

	@Transactional
	void released(UUID projectionId) {
		var record = this.entityManager.find(DatasetPublicationCredentialProjectionEntity.class, projectionId);
		if (record != null && record.releasedAt == null) {
			record.releasedAt = this.clock.instant();
		}
	}

}
