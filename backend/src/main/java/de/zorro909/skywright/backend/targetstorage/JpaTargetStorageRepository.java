package de.zorro909.skywright.backend.targetstorage;

import jakarta.persistence.EntityManager;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaTargetStorageRepository implements TargetStorageRepository {

	private final EntityManager entityManager;

	JpaTargetStorageRepository(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	@Override
	public Optional<TargetStorageAggregate> findById(UUID id) {
		return Optional.ofNullable(this.entityManager.find(TargetStorageEntity.class, id))
			.map(TargetStorageEntity::domain);
	}

	@Override
	public TargetStorageResourceClaim saveNewAndClaim(TargetStorageAggregate storage, URI endpoint, String bucket) {
		this.entityManager.persist(TargetStorageEntity.from(storage.snapshot()));
		this.entityManager.flush();
		return this.claimResource(storage.id(), storage.purpose(), endpoint, bucket);
	}

	@Override
	public TargetStorageResourceClaim claimResource(UUID storageId, TargetStoragePurpose purpose, URI endpoint,
			String bucket) {
		String resourceKey = TargetStorageResourceClaim.resourceKey(endpoint, bucket);
		TargetStorageResourceEntity existing = this.entityManager.find(TargetStorageResourceEntity.class, resourceKey);
		if (existing != null) {
			return existing.domain();
		}
		TargetStorageResourceEntity claim = TargetStorageResourceEntity.create(storageId, purpose, endpoint, bucket);
		this.entityManager.persist(claim);
		return claim.domain();
	}

	@Override
	public List<TargetStorageAggregate> findAll() {
		return this.entityManager
			.createQuery("select distinct storage from TargetStorageEntity storage order by storage.name",
					TargetStorageEntity.class)
			.getResultStream()
			.map(TargetStorageEntity::domain)
			.toList();
	}

	@Override
	public void save(TargetStorageAggregate storage) {
		TargetStorageSnapshot snapshot = storage.snapshot();
		TargetStorageEntity existing = this.entityManager.find(TargetStorageEntity.class, snapshot.id());
		if (existing == null) {
			this.entityManager.persist(TargetStorageEntity.from(snapshot));
		}
		else {
			existing.apply(snapshot);
		}
	}

	@Override
	public void delete(UUID id) {
		TargetStorageEntity entity = this.entityManager.find(TargetStorageEntity.class, id);
		if (entity != null) {
			this.entityManager.remove(entity);
		}
	}

	@Override
	public boolean hasReferences(UUID id) {
		Long references = this.entityManager.createQuery(
				"select count(defaults) from TargetStorageDefaultsEntity defaults where defaults.executionStorageId = :id or defaults.repatriationStorageId = :id",
				Long.class)
			.setParameter("id", id)
			.getSingleResult();
		return references > 0L;
	}

	@Override
	public void saveDefaults(TargetStorageDefaults defaults) {
		this.entityManager.merge(TargetStorageDefaultsEntity.from(defaults));
	}

	@Override
	public Optional<TargetStorageDefaults> findDefaults(TargetClass targetClass) {
		return Optional.ofNullable(this.entityManager.find(TargetStorageDefaultsEntity.class, targetClass))
			.map(TargetStorageDefaultsEntity::domain);
	}

	@Override
	public List<TargetStorageDefaults> findDefaults() {
		return this.entityManager
			.createQuery("select defaults from TargetStorageDefaultsEntity defaults order by defaults.targetClass",
					TargetStorageDefaultsEntity.class)
			.getResultStream()
			.map(TargetStorageDefaultsEntity::domain)
			.toList();
	}

}
