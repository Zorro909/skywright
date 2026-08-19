package de.zorro909.skywright.backend.targetstorage;

import de.zorro909.skywright.backend.boundary.generated.api.TargetStorageDefaultsApi;
import de.zorro909.skywright.backend.boundary.generated.api.TargetStoragesApi;
import de.zorro909.skywright.backend.boundary.generated.model.AssignTargetStorageDefaults;
import de.zorro909.skywright.backend.boundary.generated.model.BindingReadiness;
import de.zorro909.skywright.backend.boundary.generated.model.CreateTargetStorage;
import de.zorro909.skywright.backend.boundary.generated.model.ReplaceTargetStorageBindings;
import de.zorro909.skywright.backend.boundary.generated.model.SetTargetStorageActivation;
import de.zorro909.skywright.backend.boundary.generated.model.StageTargetStorageRevision;
import de.zorro909.skywright.backend.boundary.generated.model.TargetStorage;
import de.zorro909.skywright.backend.boundary.generated.model.TargetStorageBindingReference;
import de.zorro909.skywright.backend.boundary.generated.model.TargetStorageRevision;
import de.zorro909.skywright.backend.boundary.generated.model.TargetStorageRevisionState;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TargetStorageHttpAdapter implements TargetStoragesApi, TargetStorageDefaultsApi {

	private final TargetStorageRegistry registry;

	private final TargetStorageQualification qualification;

	private final TargetStorageBindingReadiness bindingReadiness;

	TargetStorageHttpAdapter(TargetStorageRegistry registry, TargetStorageQualification qualification,
			TargetStorageBindingReadiness bindingReadiness) {
		this.registry = registry;
		this.qualification = qualification;
		this.bindingReadiness = bindingReadiness;
	}

	public ResponseEntity<TargetStorage> createTargetStorage(CreateTargetStorage request) {
		UUID id = this.registry.register(request.getName(),
				TargetStorageHttpAdapter.purpose(request.getPurpose().getValue()), request.getBucket(),
				this.configuration(request.getConfiguration()),
				request.getBindings().stream().map(this::binding).toList());
		this.qualification.qualifyWhenReady(id);
		return ResponseEntity.status(201).body(this.storage(this.registry.get(id)));
	}

	public ResponseEntity<Void> deleteTargetStorage(UUID storageId) {
		this.registry.delete(storageId);
		return ResponseEntity.noContent().build();
	}

	public ResponseEntity<TargetStorage> getTargetStorage(UUID storageId) {
		return ResponseEntity.ok(this.storage(this.registry.get(storageId)));
	}

	public ResponseEntity<List<TargetStorage>> listTargetStorages() {
		return ResponseEntity.ok(this.registry.list().stream().map(this::storage).toList());
	}

	public ResponseEntity<TargetStorage> replaceTargetStorageBindings(UUID storageId,
			ReplaceTargetStorageBindings request) {
		this.registry.replaceBindings(storageId, request.getExpectedRegistrationRevision(),
				request.getBindings().stream().map(this::binding).toList());
		this.qualification.qualifyWhenReady(storageId);
		return ResponseEntity.ok(this.storage(this.registry.get(storageId)));
	}

	public ResponseEntity<TargetStorage> qualifyTargetStorage(UUID storageId) {
		this.refreshBindingReadiness(storageId);
		return ResponseEntity.ok(this.storage(this.qualification.qualify(storageId)));
	}

	public ResponseEntity<TargetStorage> setTargetStorageActivation(UUID storageId,
			SetTargetStorageActivation request) {
		if (request.getActivated().booleanValue()) {
			this.registry.activate(storageId, request.getExpectedRegistrationRevision());
		}
		else {
			this.registry.deactivate(storageId, request.getExpectedRegistrationRevision());
		}
		return ResponseEntity.ok(this.storage(this.registry.get(storageId)));
	}

	public ResponseEntity<TargetStorage> stageTargetStorageRevision(UUID storageId,
			StageTargetStorageRevision request) {
		this.registry.stageRevision(storageId, request.getExpectedRegistrationRevision(),
				this.configuration(request.getConfiguration()));
		this.qualification.qualifyWhenReady(storageId);
		return ResponseEntity.ok(this.storage(this.registry.get(storageId)));
	}

	public ResponseEntity<de.zorro909.skywright.backend.boundary.generated.model.TargetStorageDefaults> assignTargetStorageDefaults(
			de.zorro909.skywright.backend.boundary.generated.model.TargetClass generatedTargetClass,
			AssignTargetStorageDefaults request) {
		TargetClass targetClass = TargetStorageHttpAdapter.targetClass(generatedTargetClass.getValue());
		this.registry.assignDefaults(targetClass, request.getExecutionStorageId(), request.getRepatriationEnabled(),
				request.getRepatriationStorageId());
		return ResponseEntity.ok(this.defaults(this.registry.defaults()
			.stream()
			.filter(value -> value.targetClass() == targetClass)
			.findFirst()
			.orElseThrow()));
	}

	public ResponseEntity<List<de.zorro909.skywright.backend.boundary.generated.model.TargetStorageDefaults>> listTargetStorageDefaults() {
		return ResponseEntity.ok(this.registry.defaults().stream().map(this::defaults).toList());
	}

	private TargetStorage storage(TargetStorageView value) {
		de.zorro909.skywright.backend.boundary.generated.model.TargetStorageConfiguration configuration = value
			.configuration() == null ? null : this.configuration(value.configuration());
		return new TargetStorage(value.id(), value.name(),
				de.zorro909.skywright.backend.boundary.generated.model.TargetStoragePurpose
					.fromValue(value.purpose().wireValue()),
				value.bucket(), value.registrationRevision(), value.activated(), value.eligible(),
				value.activeRevision(), value.candidateRevision(),
				de.zorro909.skywright.backend.boundary.generated.model.CapabilityAvailability
					.fromValue(TargetStorageHttpAdapter.availabilityValue(value.availability())),
				configuration, value.revisions().stream().map(this::revision).toList(),
				value.bindings().stream().map(this::binding).toList(),
				value.assessments().stream().map(this::assessment).toList());
	}

	private TargetStorageRevision revision(TargetStorageRevisionView value) {
		return new TargetStorageRevision(value.revision(), TargetStorageRevisionState.fromValue(value.state()),
				this.configuration(value.configuration()));
	}

	private de.zorro909.skywright.backend.boundary.generated.model.TargetStorageConfiguration configuration(
			TargetStorageConfiguration value) {
		return new de.zorro909.skywright.backend.boundary.generated.model.TargetStorageConfiguration(
				value.endpoint().toString(), value.region(), value.pathStyleAccess(), value.compatibilityOptions());
	}

	private TargetStorageConfiguration configuration(
			de.zorro909.skywright.backend.boundary.generated.model.TargetStorageConfiguration value) {
		return new TargetStorageConfiguration(TargetStorageHttpAdapter.parseEndpoint(value.getEndpoint()),
				value.getRegion(), value.getPathStyleAccess(), value.getCompatibilityOptions());
	}

	static java.net.URI parseEndpoint(String value) {
		try {
			return java.net.URI.create(value);
		}
		catch (IllegalArgumentException invalid) {
			throw new TargetStorageValidationException("TARGET_STORAGE_CONFIGURATION_INVALID",
					"endpoint must be a valid URI");
		}
	}

	private de.zorro909.skywright.backend.boundary.generated.model.TargetStorageBinding binding(
			TargetStorageBinding value) {
		return new de.zorro909.skywright.backend.boundary.generated.model.TargetStorageBinding(
				de.zorro909.skywright.backend.boundary.generated.model.TargetStorageRole
					.fromValue(TargetStorageHttpAdapter.roleValue(value.role())),
				value.bindingId(), value.bindingRevision(),
				BindingReadiness.fromValue(value.readiness().name().toLowerCase(Locale.ROOT)));
	}

	private TargetStorageBinding binding(TargetStorageBindingReference value) {
		return new TargetStorageBinding(TargetStorageHttpAdapter.role(value.getRole().getValue()), value.getBindingId(),
				value.getBindingRevision(), this.bindingReadiness.readiness(value.getBindingId(),
						value.getBindingRevision(), value.getRole().getValue()));
	}

	private void refreshBindingReadiness(UUID storageId) {
		TargetStorageView storage = this.registry.get(storageId);
		List<TargetStorageBinding> refreshed = storage.bindings()
			.stream()
			.map(binding -> new TargetStorageBinding(binding.role(), binding.bindingId(), binding.bindingRevision(),
					this.bindingReadiness.readiness(binding.bindingId(), binding.bindingRevision(),
							TargetStorageHttpAdapter.roleValue(binding.role()))))
			.toList();
		if (!refreshed.equals(storage.bindings())) {
			this.registry.replaceBindings(storageId, storage.registrationRevision(), refreshed);
		}
	}

	private de.zorro909.skywright.backend.boundary.generated.model.TargetStorageAssessment assessment(
			TargetStorageAssessment value) {
		return new de.zorro909.skywright.backend.boundary.generated.model.TargetStorageAssessment(value.id(),
				value.configurationRevision(), value.observedFrom().atOffset(ZoneOffset.UTC),
				value.observedUntil().atOffset(ZoneOffset.UTC),
				de.zorro909.skywright.backend.boundary.generated.model.CapabilityAvailability
					.fromValue(TargetStorageHttpAdapter.availabilityValue(value.availability())),
				value.bindingRevisions()
					.stream()
					.map(binding -> new TargetStorageBindingReference(
							de.zorro909.skywright.backend.boundary.generated.model.TargetStorageRole
								.fromValue(TargetStorageHttpAdapter.roleValue(binding.role())),
							binding.bindingId(), binding.bindingRevision()))
					.toList(),
				value.capabilities().stream().map(this::capability).toList());
	}

	private de.zorro909.skywright.backend.boundary.generated.model.TargetStorageCapabilityResult capability(
			TargetStorageCapabilityResult value) {
		return new de.zorro909.skywright.backend.boundary.generated.model.TargetStorageCapabilityResult(
				value.capability(), value.succeeded(), value.failureCode(), value.summary(), value.observations());
	}

	private de.zorro909.skywright.backend.boundary.generated.model.TargetStorageDefaults defaults(
			TargetStorageDefaults value) {
		return new de.zorro909.skywright.backend.boundary.generated.model.TargetStorageDefaults(
				de.zorro909.skywright.backend.boundary.generated.model.TargetClass
					.fromValue(value.targetClass().wireValue()),
				value.executionStorageId(), value.repatriationEnabled(), value.repatriationStorageId());
	}

	private static TargetStoragePurpose purpose(String value) {
		return "dataset".equals(value) ? TargetStoragePurpose.DATASET : TargetStoragePurpose.RUN_OUTPUT;
	}

	private static TargetClass targetClass(String value) {
		return Map
			.of("local-single-gpu", TargetClass.LOCAL_SINGLE_GPU, "local-multi-gpu", TargetClass.LOCAL_MULTI_GPU,
					"cloud-on-demand", TargetClass.CLOUD_ON_DEMAND, "cloud-spot", TargetClass.CLOUD_SPOT)
			.get(value);
	}

	private static TargetStorageRole role(String value) {
		return TargetStorageRole.valueOf(value.replace('-', '_').toUpperCase(Locale.ROOT));
	}

	private static String roleValue(TargetStorageRole value) {
		return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
	}

	private static String availabilityValue(CapabilityAvailability value) {
		return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
	}

}
