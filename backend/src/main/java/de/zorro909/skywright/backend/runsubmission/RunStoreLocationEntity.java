package de.zorro909.skywright.backend.runsubmission;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Skywright's current location pointer; the accepted execution destination stays pinned.
 */
@Entity
@Table(name = "run_store_location", schema = "skywright")
class RunStoreLocationEntity {

	@Id
	@Column(name = "run_id", updatable = false)
	UUID runId;

	@Column(name = "storage_id", nullable = false)
	UUID storageId;

	protected RunStoreLocationEntity() {
	}

	RunStoreLocationEntity(AcceptedRun run) {
		runId = run.runId();
		storageId = UUID.fromString(run.definition().value().at("/storage/execution/storageId").asText());
	}

}
