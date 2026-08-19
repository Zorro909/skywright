package de.zorro909.skywright.backend.targetstorage;

import de.zorro909.skywright.backend.runstore.ResolvedTargetStorage;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

@Transactional
public class TargetStorageRegistry {

	private final TargetStorageRepository repository;

	private final TargetStorageBindingReadiness bindingReadiness;

	private final Optional<TargetStorageCredentialAccess> credentialAccess;

	private final TargetStorageReferenceCheck referenceCheck;

	public TargetStorageRegistry(TargetStorageRepository repository, TargetStorageBindingReadiness bindingReadiness,
			Optional<TargetStorageCredentialAccess> credentialAccess, TargetStorageReferenceCheck referenceCheck) {
		this.repository = repository;
		this.bindingReadiness = bindingReadiness;
		this.credentialAccess = credentialAccess;
		this.referenceCheck = referenceCheck;
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
		return this.repository.findAll().stream().map(this::view).toList();
	}

	@Transactional(readOnly = true)
	public TargetStorageView get(UUID id) {
		return this.view(this.storage(id));
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
		if (this.repository.hasReferences(id) || this.referenceCheck.hasDurableReference(id)) {
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
	TargetStorageDescriptor resolveDescriptor(UUID id) {
		TargetStorageAggregate storage = this.requireRunOutput(id);
		if (storage.activeRevision() == null) {
			throw new TargetStorageIneligibleException("TARGET_STORAGE_NOT_QUALIFIED",
					"Target Storage has no active qualified revision");
		}
		return storage.descriptor();
	}

	@Transactional(readOnly = true)
	public ResolvedTargetStorage resolveRunStore(UUID id, TargetStorageRole role, String trainingProjectId,
			String runId) {
		TargetStorageAggregate storage = this.storage(id);
		TargetStorageDescriptor descriptor = this.resolveDescriptor(id);
		TargetStorageBinding binding = this.currentBindings(storage)
			.stream()
			.filter(candidate -> candidate.role() == role && candidate.readiness() == BindingReadiness.READY)
			.findFirst()
			.orElseThrow(() -> new TargetStorageIneligibleException("TARGET_STORAGE_BINDING_NOT_READY",
					"The requested role has no ready Credential Binding"));
		var provider = this.credentialAccess
			.flatMap(access -> access.credentials(binding.bindingId(), binding.bindingRevision(), role.wireValue()))
			.orElseThrow(() -> new TargetStorageIneligibleException("TARGET_STORAGE_CREDENTIAL_UNAVAILABLE",
					"The requested Credential Projection is temporarily unavailable"));
		return new ResolvedTargetStorage(descriptor.storageId().toString(), descriptor.endpoint(), descriptor.bucket(),
				software.amazon.awssdk.regions.Region.of(descriptor.region()), descriptor.pathStyleAccess(), provider,
				trainingProjectId, runId, descriptor.compatibilityOptions());
	}

	TargetStorageQualificationRequest qualificationRequest(UUID id) {
		TargetStorageAggregate storage = this.storage(id);
		return storage.qualificationRequest(this.currentBindings(storage));
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
		TargetStorageAggregate storage = this.requireRunOutput(id);
		if (!storage.eligible(this.currentBindings(storage))) {
			throw new TargetStorageIneligibleException("TARGET_STORAGE_INELIGIBLE",
					"Target Storage is not eligible for new work");
		}
	}

	private TargetStorageAggregate storage(UUID id) {
		return this.repository.findById(id).orElseThrow(() -> new TargetStorageNotFoundException(id));
	}

	private TargetStorageView view(TargetStorageAggregate storage) {
		return storage.view(this.currentBindings(storage));
	}

	private List<TargetStorageBinding> currentBindings(TargetStorageAggregate storage) {
		return storage.bindings()
			.stream()
			.map(binding -> new TargetStorageBinding(binding.role(), binding.bindingId(), binding.bindingRevision(),
					this.bindingReadiness.readiness(binding.bindingId(), binding.bindingRevision(),
							binding.role().wireValue())))
			.toList();
	}

	private static void requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		if (value.length() > 255) {
			throw new IllegalArgumentException(field + " must not exceed 255 characters");
		}
	}

}
