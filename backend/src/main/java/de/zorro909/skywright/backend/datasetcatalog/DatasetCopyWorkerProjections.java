package de.zorro909.skywright.backend.datasetcatalog;

import jakarta.persistence.EntityManager;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DatasetCopyWorkerProjections {

	private final EntityManager entities;

	DatasetCopyWorkerProjections(EntityManager entities) {
		this.entities = entities;
	}

	UUID projected(UUID copyId, UUID bindingId, long revision) {
		var projection = new DatasetCopyWorkerProjection(copyId, bindingId, revision, Instant.now());
		this.entities.persist(projection);
		this.entities.flush();
		return projection.projectionId;
	}

	void prepared(UUID id, Path directory) {
		this.require(id).jobDirectory = directory.toString();
	}

	void launched(UUID id, long pid, Instant startedAt) {
		var projection = this.require(id);
		projection.workerPid = pid;
		projection.workerStartedAt = startedAt;
	}

	void released(UUID id) {
		this.require(id).releasedAt = Instant.now();
	}

	@Transactional(readOnly = true)
	List<Open> open() {
		return this.entities
			.createQuery("select p from DatasetCopyWorkerProjection p where p.releasedAt is null",
					DatasetCopyWorkerProjection.class)
			.getResultStream()
			.map(p -> new Open(p.projectionId, p.copyId, p.workerPid, p.workerStartedAt, p.jobDirectory))
			.toList();
	}

	private DatasetCopyWorkerProjection require(UUID id) {
		return java.util.Objects.requireNonNull(this.entities.find(DatasetCopyWorkerProjection.class, id));
	}

	record Open(UUID id, UUID copyId, Long pid, Instant startedAt, String directory) {
	}

}
