package de.zorro909.skywright.backend.targetstorage;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

@Transactional
public class TargetStorageRegistry {

	private final TargetStorageRepository repository;

	public TargetStorageRegistry(TargetStorageRepository repository) {
		this.repository = repository;
	}

	public UUID register(String name, TargetStoragePurpose purpose, String bucket,
			TargetStorageConfiguration configuration, List<TargetStorageBinding> bindings) {
		TargetStorageRegistry.requireText(name, "name");
		TargetStorageRegistry.requireText(bucket, "bucket");
		this.repository.findByResource(configuration.endpoint(), bucket).ifPresent(existing -> {
			if (existing.purpose() != purpose) {
				throw new TargetStorageConflictException("TARGET_STORAGE_PURPOSE_CONFLICT",
						"The endpoint and bucket are already registered for " + existing.purpose().wireValue());
			}
			throw new TargetStorageConflictException("TARGET_STORAGE_RESOURCE_CONFLICT",
					"The endpoint and bucket are already registered");
		});
		UUID id = UUID.randomUUID();
		this.repository.save(TargetStorageAggregate.create(id, name, purpose, bucket, configuration, bindings));
		return id;
	}

	@Transactional(readOnly = true)
	public List<TargetStorageView> list() {
		return this.repository.findAll().stream().map(TargetStorageAggregate::view).toList();
	}

	@Transactional(readOnly = true)
	public TargetStorageView get(UUID id) {
		return this.storage(id).view();
	}

	public void rename(UUID id, long expectedRegistrationRevision, String name) {
		TargetStorageRegistry.requireText(name, "name");
		TargetStorageAggregate storage = this.storage(id);
		storage.requireRevision(expectedRegistrationRevision);
		storage.rename(name);
		this.repository.save(storage);
	}

	public long stageRevision(UUID id, long expectedRegistrationRevision, TargetStorageConfiguration configuration) {
		TargetStorageAggregate storage = this.storage(id);
		storage.requireRevision(expectedRegistrationRevision);
		this.repository.findByResource(configuration.endpoint(), storage.bucket()).ifPresent(existing -> {
			if (!existing.id().equals(id)) {
				if (existing.purpose() != storage.purpose()) {
					throw new TargetStorageConflictException("TARGET_STORAGE_PURPOSE_CONFLICT",
							"The endpoint and bucket are already registered for another purpose");
				}
				throw new TargetStorageConflictException("TARGET_STORAGE_RESOURCE_CONFLICT",
						"The endpoint and bucket are already registered");
			}
		});
		long revision = storage.stage(configuration);
		this.repository.save(storage);
		return revision;
	}

	public void replaceBindings(UUID id, long expectedRegistrationRevision, List<TargetStorageBinding> bindings) {
		TargetStorageAggregate storage = this.storage(id);
		storage.requireRevision(expectedRegistrationRevision);
		storage.replaceBindings(bindings);
		this.repository.save(storage);
	}

	public void recordQualification(UUID id, TargetStorageAssessment assessment) {
		TargetStorageAggregate storage = this.storage(id);
		storage.record(assessment);
		this.repository.save(storage);
	}

	public void activate(UUID id, long expectedRegistrationRevision) {
		TargetStorageAggregate storage = this.storage(id);
		storage.requireRevision(expectedRegistrationRevision);
		storage.activate();
		this.repository.save(storage);
	}

	public void deactivate(UUID id, long expectedRegistrationRevision) {
		TargetStorageAggregate storage = this.storage(id);
		storage.requireRevision(expectedRegistrationRevision);
		storage.deactivate();
		this.repository.save(storage);
	}

	public void delete(UUID id) {
		this.storage(id);
		if (this.repository.hasReferences(id)) {
			throw new TargetStorageReferencedException();
		}
		this.repository.delete(id);
	}

	public void assignDefaults(TargetClass targetClass, UUID executionStorageId, boolean repatriationEnabled,
			UUID repatriationStorageId) {
		this.requireEligibleRunOutput(executionStorageId);
		this.requireEligibleRunOutput(repatriationStorageId);
		this.repository.saveDefaults(
				new TargetStorageDefaults(targetClass, executionStorageId, repatriationEnabled, repatriationStorageId));
	}

	@Transactional(readOnly = true)
	public List<TargetStorageDefaults> defaults() {
		return this.repository.findDefaults();
	}

	@Transactional(readOnly = true)
	public TargetStorageSelection resolve(TargetClass targetClass, UUID executionOverride,
			RepatriationOverride repatriationOverride) {
		UUID repatriation;
		TargetStorageDefaults defaults = this.repository.findDefaults(targetClass)
			.orElseThrow(() -> new TargetStorageIneligibleException("TARGET_STORAGE_DEFAULT_MISSING",
					"No Target Storage defaults are assigned for " + targetClass.wireValue()));
		UUID execution = executionOverride == null ? defaults.executionStorageId() : executionOverride;
		this.requireEligibleRunOutput(execution);
		boolean repatriationEnabled = repatriationOverride == null ? defaults.repatriationEnabled()
				: repatriationOverride.enabled();
		repatriation = repatriationOverride == null ? defaults.repatriationStorageId()
				: repatriationOverride.storageId();
		if (repatriationEnabled) {
			this.requireEligibleRunOutput(repatriation);
		}
		return new TargetStorageSelection(execution, repatriationEnabled, repatriation);
	}

	@Transactional(readOnly = true)
	public TargetStorageDescriptor resolveDescriptor(UUID id) {
		TargetStorageAggregate storage = this.storage(id);
		if (storage.activeRevision() == null) {
			throw new TargetStorageIneligibleException("TARGET_STORAGE_NOT_QUALIFIED",
					"Target Storage has no active qualified revision");
		}
		return storage.descriptor();
	}

	TargetStorageQualificationRequest qualificationRequest(UUID id) {
		TargetStorageAggregate storage = this.storage(id);
		return storage.qualificationRequest();
	}

	private TargetStorageAggregate requireRunOutput(UUID id) {
		TargetStorageAggregate storage = this.storage(Objects.requireNonNull(id, "storageId"));
		if (storage.purpose() != TargetStoragePurpose.RUN_OUTPUT) {
			throw new TargetStorageIneligibleException("TARGET_STORAGE_WRONG_PURPOSE",
					"Run-output selection requires a run-output Target Storage");
		}
		return storage;
	}

	private void requireEligibleRunOutput(UUID id) {
		if (!this.requireRunOutput(id).eligible()) {
			throw new TargetStorageIneligibleException("TARGET_STORAGE_INELIGIBLE",
					"Target Storage is not eligible for new work");
		}
	}

	private TargetStorageAggregate storage(UUID id) {
		return this.repository.findById(id).orElseThrow(() -> new TargetStorageNotFoundException(id));
	}

	private static void requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
	}

}
