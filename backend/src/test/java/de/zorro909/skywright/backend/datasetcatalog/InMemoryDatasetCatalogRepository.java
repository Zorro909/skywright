package de.zorro909.skywright.backend.datasetcatalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class InMemoryDatasetCatalogRepository implements DatasetCatalogRepository {

	private final Map<UUID, DatasetCatalogAggregate> values = new LinkedHashMap<>();

	@Override
	public Optional<DatasetCatalogAggregate> findByDefinitionId(UUID definitionId) {
		return Optional.ofNullable(this.values.get(definitionId));
	}

	@Override
	public Optional<DatasetCatalogAggregate> findByDatasetAndVersionLabel(UUID datasetId, String versionLabel) {
		return this.values.values()
			.stream()
			.filter(value -> value.view().definition().datasetId().equals(datasetId))
			.filter(value -> java.util.Objects.equals(value.view().definition().versionLabel(), versionLabel))
			.findFirst();
	}

	@Override
	public List<DatasetCatalogAggregate> findAll() {
		return List.copyOf(this.values.values());
	}

	@Override
	public void save(DatasetCatalogAggregate catalog) {
		this.values.put(catalog.view().definition().definitionId(), catalog);
	}

}
