package de.zorro909.skywright.backend.datasetcatalog;

import java.util.UUID;

@FunctionalInterface
public interface DatasetTargetStorageEligibility {

	boolean eligible(UUID storageId);

}
