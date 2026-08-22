package de.zorro909.skywright.backend.datasetcatalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface DatasetCatalogRepository {

	Optional<DatasetCatalogAggregate> findByDefinitionId(UUID definitionId);

	Optional<DatasetCatalogAggregate> findByDatasetAndVersionLabel(UUID datasetId, String versionLabel);

	List<DatasetCatalogAggregate> findAll();

	void save(DatasetCatalogAggregate catalog);

}
