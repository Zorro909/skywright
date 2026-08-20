package de.zorro909.skywright.backend.trainingproject;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class TrainingProjectRepository {

	private final EntityManager entityManager;

	TrainingProjectRepository(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	void create(TrainingProjectEntity project) {
		this.entityManager.persist(project);
		this.entityManager.flush();
	}

	Optional<TrainingProjectEntity> find(UUID id) {
		return Optional.ofNullable(this.entityManager.find(TrainingProjectEntity.class, id));
	}

	List<TrainingProjectEntity> findAll() {
		return this.entityManager
			.createQuery("select project from TrainingProjectEntity project order by lower(project.displayName)",
					TrainingProjectEntity.class)
			.getResultList();
	}

	boolean nameExists(String displayName, UUID except) {
		return count("select count(project) from TrainingProjectEntity project "
				+ "where lower(trim(project.displayName)) = lower(:value) and (:except is null or project.id <> :except)",
				displayName, except) > 0;
	}

	boolean repositoryExists(String repository) {
		Long count = this.entityManager
			.createQuery(
					"select count(binding) from TrainingProjectEntity project join project.bindings binding "
							+ "where binding.repository = :repository and binding.state in ('active', 'candidate')",
					Long.class)
			.setParameter("repository", repository)
			.getSingleResult();
		return count > 0;
	}

	void createOperation(RegistryRebindingOperationEntity operation) {
		this.entityManager.persist(operation);
	}

	Optional<RegistryRebindingOperationEntity> findOperation(UUID id) {
		return Optional.ofNullable(this.entityManager.find(RegistryRebindingOperationEntity.class, id));
	}

	boolean hasActiveOperation(UUID projectId) {
		Long count = this.entityManager
			.createQuery(
					"select count(operation) from RegistryRebindingOperationEntity operation "
							+ "where operation.projectId = :projectId and operation.state in ('verifying', 'failed')",
					Long.class)
			.setParameter("projectId", projectId)
			.getSingleResult();
		return count > 0;
	}

	private long count(String query, String value, UUID except) {
		return this.entityManager.createQuery(query, Long.class)
			.setParameter("value", value)
			.setParameter("except", except)
			.getSingleResult();
	}

}
