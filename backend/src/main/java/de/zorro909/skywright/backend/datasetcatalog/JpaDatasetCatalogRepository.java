package de.zorro909.skywright.backend.datasetcatalog;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaDatasetCatalogRepository implements DatasetCatalogRepository {

	private final EntityManager entityManager;

	JpaDatasetCatalogRepository(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	@Override
	public Optional<DatasetCatalogAggregate> findByDefinitionId(UUID definitionId) {
		return Optional.ofNullable(this.entityManager.find(DatasetCatalogEntity.class, definitionId))
			.map(DatasetCatalogEntity::domain);
	}

	@Override
	public Optional<DatasetCatalogAggregate> findByDatasetAndVersionLabel(UUID datasetId, String versionLabel) {
		return this.entityManager.createQuery(
				"select catalog from DatasetCatalogEntity catalog where catalog.datasetId = :datasetId and catalog.versionLabel = :versionLabel",
				DatasetCatalogEntity.class)
			.setParameter("datasetId", datasetId)
			.setParameter("versionLabel", versionLabel)
			.getResultStream()
			.findFirst()
			.map(DatasetCatalogEntity::domain);
	}

	@Override
	public List<DatasetCatalogAggregate> findAll() {
		return this.entityManager
			.createQuery("select distinct catalog from DatasetCatalogEntity catalog order by catalog.createdAt",
					DatasetCatalogEntity.class)
			.getResultStream()
			.map(DatasetCatalogEntity::domain)
			.toList();
	}

	@Override
	public void save(DatasetCatalogAggregate catalog) {
		DatasetCatalogSnapshot snapshot = catalog.snapshot();
		DatasetCatalogEntity existing = this.entityManager.find(DatasetCatalogEntity.class,
				snapshot.definition().definitionId());
		if (existing == null) {
			this.entityManager.persist(DatasetCatalogEntity.from(snapshot));
		}
		else {
			existing.apply(snapshot);
		}
	}

}
