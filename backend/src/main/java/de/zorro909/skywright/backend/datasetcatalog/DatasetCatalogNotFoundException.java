package de.zorro909.skywright.backend.datasetcatalog;

import java.util.UUID;

public final class DatasetCatalogNotFoundException extends DatasetCatalogException {

	DatasetCatalogNotFoundException(UUID definitionId) {
		super("DATASET_DEFINITION_NOT_FOUND", "Dataset Definition " + definitionId + " does not exist");
	}

}
