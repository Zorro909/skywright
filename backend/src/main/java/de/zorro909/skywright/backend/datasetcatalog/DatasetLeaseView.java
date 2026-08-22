package de.zorro909.skywright.backend.datasetcatalog;

import java.time.Instant;
import java.util.UUID;

public record DatasetLeaseView(UUID id, UUID runRecordId, UUID definitionId, UUID copyId, long generation,
		Instant acquiredAt, Instant endedAt, String endReason) {

	public boolean active() {
		return this.endedAt == null;
	}

}
