package de.zorro909.skywright.backend.targetstorage;

import java.net.URI;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class InMemoryTargetStorageRepository implements TargetStorageRepository {

	private final Map<UUID, TargetStorageAggregate> storages = new LinkedHashMap<>();

	private final Map<TargetClass, TargetStorageDefaults> defaults = new EnumMap<>(TargetClass.class);

	private final Map<String, TargetStorageResourceClaim> resourceClaims = new LinkedHashMap<>();

	InMemoryTargetStorageRepository() {
	}

	@Override
	public Optional<TargetStorageAggregate> findById(UUID id) {
		return Optional.ofNullable(this.storages.get(id));
	}

	@Override
	public TargetStorageResourceClaim saveNewAndClaim(TargetStorageAggregate storage, URI endpoint, String bucket) {
		TargetStorageResourceClaim claim = this.claim(storage.id(), storage.purpose(), endpoint, bucket);
		if (claim.storageId().equals(storage.id())) {
			this.storages.put(storage.id(), storage);
		}
		return claim;
	}

	@Override
	public TargetStorageResourceClaim claimResource(UUID storageId, TargetStoragePurpose purpose, URI endpoint,
			String bucket) {
		return this.claim(storageId, purpose, endpoint, bucket);
	}

	@Override
	public List<TargetStorageAggregate> findAll() {
		return List.copyOf(this.storages.values());
	}

	@Override
	public void save(TargetStorageAggregate storage) {
		this.storages.put(storage.id(), storage);
	}

	@Override
	public void delete(UUID id) {
		this.storages.remove(id);
		this.resourceClaims.values().removeIf(claim -> claim.storageId().equals(id));
	}

	@Override
	public boolean hasReferences(UUID id) {
		return this.defaults.values()
			.stream()
			.anyMatch(value -> id.equals(value.executionStorageId()) || id.equals(value.repatriationStorageId()));
	}

	@Override
	public void saveDefaults(TargetStorageDefaults value) {
		this.defaults.put(value.targetClass(), value);
	}

	@Override
	public Optional<TargetStorageDefaults> findDefaults(TargetClass targetClass) {
		return Optional.ofNullable(this.defaults.get(targetClass));
	}

	@Override
	public List<TargetStorageDefaults> findDefaults() {
		return List.copyOf(this.defaults.values());
	}

	private TargetStorageResourceClaim claim(UUID storageId, TargetStoragePurpose purpose, URI endpoint,
			String bucket) {
		String key = TargetStorageResourceClaim.resourceKey(endpoint, bucket);
		return this.resourceClaims.computeIfAbsent(key, ignored -> new TargetStorageResourceClaim(storageId, purpose));
	}

}
