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
		UUID id = UUID.randomUUID();
		TargetStorageResourceClaim claim = this.repository.saveNewAndClaim(
				TargetStorageAggregate.create(id, name, purpose, bucket, configuration, bindings),
				configuration.endpoint(), bucket);
		this.requireClaim(id, purpose, claim);
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

	public long stageRevision(UUID id, long expectedRegistrationRevision, TargetStorageConfiguration configuration) {
		TargetStorageAggregate storage = this.storage(id);
		storage.requireRevision(expectedRegistrationRevision);
		this.requireClaim(id, storage.purpose(),
				this.repository.claimResource(id, storage.purpose(), configuration.endpoint(), storage.bucket()));
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

	/**
	 * Resolves and snapshots both definition-owned storage selections without
	 * credentials.
	 */
	@Transactional(readOnly = true)
	public RunDefinitionStorageSelection resolveForRunDefinition(String targetClass, UUID executionOverride,
			Boolean repatriationEnabledOverride, UUID repatriationStorageOverride) {
		TargetClass resolvedClass = switch (targetClass) {
			case "local-single-gpu" -> TargetClass.LOCAL_SINGLE_GPU;
			case "local-multi-gpu" -> TargetClass.LOCAL_MULTI_GPU;
			case "cloud-on-demand" -> TargetClass.CLOUD_ON_DEMAND;
			case "cloud-spot" -> TargetClass.CLOUD_SPOT;
			default -> throw new TargetStorageIneligibleException("TARGET_CLASS_INVALID", "Unknown Target Class");
		};
		TargetStorageDefaults defaults = this.repository.findDefaults(resolvedClass)
			.orElseThrow(() -> new TargetStorageIneligibleException("TARGET_STORAGE_DEFAULT_MISSING",
					"No Target Storage defaults are assigned for " + resolvedClass.wireValue()));
		UUID executionId = executionOverride == null ? defaults.executionStorageId() : executionOverride;
		boolean repatriationEnabled = repatriationEnabledOverride == null ? defaults.repatriationEnabled()
				: repatriationEnabledOverride;
		UUID destinationId = repatriationStorageOverride == null ? defaults.repatriationStorageId()
				: repatriationStorageOverride;
		TargetStorageAggregate execution = requireRunOutput(executionId);
		TargetStorageAggregate destination = requireRunOutput(destinationId);
		if (!execution.eligible() || !destination.eligible()) {
			throw new TargetStorageIneligibleException("TARGET_STORAGE_INELIGIBLE",
					"Both Run Definition storage selections must be eligible");
		}
		return new RunDefinitionStorageSelection(definitionSnapshot(execution), repatriationEnabled,
				definitionSnapshot(destination));
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

	TargetStorageResolution resolveEligibleRunOutput(UUID id, TargetStorageRole role) {
		TargetStorageAggregate storage = this.requireRunOutput(id);
		if (!storage.eligible()) {
			throw new TargetStorageIneligibleException("TARGET_STORAGE_INELIGIBLE",
					"Target Storage is not eligible for new work");
		}
		TargetStorageBinding selectedBinding = storage.bindings()
			.stream()
			.filter(binding -> binding.role() == role && binding.readiness() == BindingReadiness.READY)
			.findFirst()
			.orElseThrow(() -> new TargetStorageIneligibleException("TARGET_STORAGE_BINDING_UNAVAILABLE",
					"The required Credential Binding is not ready"));
		return new TargetStorageResolution(storage.descriptor(), selectedBinding);
	}

	TargetStorageResolution resolveDatasetMaintenance(UUID id, TargetStorageRole role) {
		TargetStorageAggregate storage = this.storage(Objects.requireNonNull(id, "storageId"));
		if (storage.purpose() != TargetStoragePurpose.DATASET || storage.activeRevision() == null) {
			throw new TargetStorageIneligibleException("TARGET_STORAGE_WRONG_PURPOSE",
					"Dataset maintenance requires a qualified dataset Target Storage");
		}
		TargetStorageBinding selectedBinding = storage.bindings()
			.stream()
			.filter(binding -> binding.role() == role && binding.readiness() == BindingReadiness.READY)
			.findFirst()
			.orElseThrow(() -> new TargetStorageIneligibleException("TARGET_STORAGE_BINDING_UNAVAILABLE",
					"The required Credential Binding is not ready"));
		return new TargetStorageResolution(storage.descriptor(), selectedBinding);
	}

	/** Reports whether a Dataset Catalog admission may use the registered storage. */
	public boolean eligibleDataset(UUID id) {
		return this.repository.findById(Objects.requireNonNull(id, "storageId"))
			.filter(storage -> storage.purpose() == TargetStoragePurpose.DATASET)
			.filter(TargetStorageAggregate::eligible)
			.isPresent();
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

	private static RunDefinitionStorageSnapshot definitionSnapshot(TargetStorageAggregate storage) {
		TargetStorageDescriptor descriptor = storage.descriptor();
		return new RunDefinitionStorageSnapshot(descriptor.storageId(), storage.registrationRevision(),
				storage.activeRevision(), descriptor.endpoint(), descriptor.bucket(), descriptor.region(),
				descriptor.pathStyleAccess(), descriptor.compatibilityOptions());
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
			throw new TargetStorageValidationException("TARGET_STORAGE_REGISTRATION_INVALID",
					field + " must not be blank");
		}
		if (value.length() > 255) {
			throw new TargetStorageValidationException("TARGET_STORAGE_REGISTRATION_INVALID",
					field + " must not exceed 255 characters");
		}
	}

	private void requireClaim(UUID id, TargetStoragePurpose purpose, TargetStorageResourceClaim claim) {
		if (claim.storageId().equals(id)) {
			return;
		}
		if (claim.purpose() != purpose) {
			throw new TargetStorageConflictException("TARGET_STORAGE_PURPOSE_CONFLICT",
					"The endpoint and bucket are already registered for " + claim.purpose().wireValue());
		}
		throw new TargetStorageConflictException("TARGET_STORAGE_RESOURCE_CONFLICT",
				"The endpoint and bucket are already registered");
	}

}
