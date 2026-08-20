package de.zorro909.skywright.backend.trainingproject;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity(name = "TrainingProjectEntity")
@Table(name = "training_project")
class TrainingProjectEntity {

	@Id
	UUID id;

	@Column(name = "display_name", nullable = false)
	String displayName;

	@Column(nullable = false)
	long revision;

	@Version
	@Column(name = "persistence_version", nullable = false)
	long persistenceVersion;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "training_project_registry_binding",
			joinColumns = @JoinColumn(name = "training_project_id"))
	@OrderColumn(name = "binding_position")
	List<RegistryBindingEmbeddable> bindings = new ArrayList<>();

	protected TrainingProjectEntity() {
	}

	static TrainingProjectEntity create(UUID id, String displayName, RegistryBinding binding) {
		var result = new TrainingProjectEntity();
		result.id = id;
		result.displayName = displayName;
		result.revision = 1;
		result.bindings.add(RegistryBindingEmbeddable.from(binding));
		return result;
	}

	TrainingProjectView view() {
		List<RegistryBinding> history = this.bindings.stream().map(RegistryBindingEmbeddable::domain).toList();
		RegistryBinding active = history.stream()
			.filter(binding -> "active".equals(binding.state()))
			.findFirst()
			.orElseThrow();
		return new TrainingProjectView(this.id, this.displayName, this.revision, active, history);
	}

	void replaceActiveBinding(RegistryBinding replacement) {
		this.bindings = this.bindings.stream().map(RegistryBindingEmbeddable::domain).map(binding -> {
			if ("active".equals(binding.state())) {
				return new RegistryBinding(binding.revision(), binding.repository(), binding.accessMode(),
						binding.resolverCredentialBindingId(), binding.executionCredentialBindingId(),
						binding.readiness(), "retired");
			}
			return binding;
		}).map(RegistryBindingEmbeddable::from).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
		this.bindings.add(RegistryBindingEmbeddable.from(replacement));
	}

	void stageCandidate(RegistryBinding candidate) {
		if (this.bindings.stream()
			.map(RegistryBindingEmbeddable::domain)
			.anyMatch(binding -> "candidate".equals(binding.state()))) {
			throw new TrainingProjectException("REGISTRY_REBINDING_CONFLICT",
					"A Registry Rebinding Operation is already active.");
		}
		this.bindings.add(RegistryBindingEmbeddable.from(candidate));
		this.revision++;
	}

	void promoteCandidate(long activeRevision, long candidateRevision) {
		List<RegistryBinding> current = this.bindings.stream().map(RegistryBindingEmbeddable::domain).toList();
		RegistryBinding active = current.stream()
			.filter(binding -> "active".equals(binding.state()))
			.findFirst()
			.orElseThrow();
		if (active.revision() != activeRevision) {
			throw new TrainingProjectException("TRAINING_PROJECT_REVISION_CONFLICT",
					"The active registry binding changed; retry against current state.");
		}
		this.bindings = current.stream().map(binding -> {
			String state = binding.revision() == activeRevision ? "retired"
					: binding.revision() == candidateRevision ? "active" : binding.state();
			return RegistryBindingEmbeddable.from(new RegistryBinding(binding.revision(), binding.repository(),
					binding.accessMode(), binding.resolverCredentialBindingId(), binding.executionCredentialBindingId(),
					binding.readiness(), state));
		}).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
		this.revision++;
	}

	void abandonCandidate(long candidateRevision) {
		this.bindings = this.bindings.stream().map(RegistryBindingEmbeddable::domain).map(binding -> {
			String state = binding.revision() == candidateRevision && "candidate".equals(binding.state()) ? "retired"
					: binding.state();
			return RegistryBindingEmbeddable.from(new RegistryBinding(binding.revision(), binding.repository(),
					binding.accessMode(), binding.resolverCredentialBindingId(), binding.executionCredentialBindingId(),
					binding.readiness(), state));
		}).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
		this.revision++;
	}

}
