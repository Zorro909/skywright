package de.zorro909.skywright.backend.datasetcatalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity(name = "DatasetCopyWorkerProjection")
@Table(name = "dataset_copy_worker_projection")
class DatasetCopyWorkerProjection {

	@Id
	@Column(name = "projection_id")
	UUID projectionId;

	@Column(name = "copy_id", nullable = false)
	UUID copyId;

	@Column(name = "binding_id", nullable = false)
	UUID bindingId;

	@Column(name = "binding_revision", nullable = false)
	long bindingRevision;

	@Column(name = "consumer_role", nullable = false)
	String consumerRole;

	@Column(name = "projected_at", nullable = false)
	Instant projectedAt;

	@Column(name = "released_at")
	Instant releasedAt;

	@Column(name = "worker_pid")
	Long workerPid;

	@Column(name = "worker_started_at")
	Instant workerStartedAt;

	@Column(name = "job_directory")
	String jobDirectory;

	protected DatasetCopyWorkerProjection() {
	}

	DatasetCopyWorkerProjection(UUID copyId, UUID bindingId, long bindingRevision, Instant projectedAt) {
		this.projectionId = UUID.randomUUID();
		this.copyId = copyId;
		this.bindingId = bindingId;
		this.bindingRevision = bindingRevision;
		this.consumerRole = "transfer-worker";
		this.projectedAt = projectedAt;
	}

}
