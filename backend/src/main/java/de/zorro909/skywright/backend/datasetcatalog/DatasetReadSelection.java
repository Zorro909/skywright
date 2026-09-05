package de.zorro909.skywright.backend.datasetcatalog;

import java.util.List;
import java.util.UUID;

/** Non-secret Dataset access inputs derived from a durable Run-owned lease. */
public record DatasetReadSelection(DatasetDefinitionView definition, List<DatasetManifestEntry> manifest,
		UUID targetStorageId, String location, DatasetLeaseView lease) {

	public DatasetReadSelection {
		manifest = List.copyOf(manifest);
	}

}
