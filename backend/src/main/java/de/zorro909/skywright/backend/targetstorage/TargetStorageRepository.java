package de.zorro909.skywright.backend.targetstorage;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface TargetStorageRepository {

	Optional<TargetStorageAggregate> findById(UUID id);

	Optional<TargetStorageAggregate> findByResource(URI endpoint, String bucket);

	List<TargetStorageAggregate> findAll();

	void save(TargetStorageAggregate storage);

	void delete(UUID id);

	boolean hasReferences(UUID id);

	void saveDefaults(TargetStorageDefaults defaults);

	Optional<TargetStorageDefaults> findDefaults(TargetClass targetClass);

	List<TargetStorageDefaults> findDefaults();

}
