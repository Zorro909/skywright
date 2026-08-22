package de.zorro909.skywright.backend.datasetcatalog;

import java.util.List;
import java.util.UUID;

public record DatasetCopyView(UUID id, UUID targetStorageId, DatasetCopyRole role, long revision,
		DatasetCopyGenerationView currentGeneration, List<DatasetCopyGenerationView> generationHistory,
		long activeLeaseCount) {
}
