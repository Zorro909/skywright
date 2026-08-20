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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity(name = "RegistryRebindingOperationEntity")
@Table(name = "registry_rebinding_operation")
class RegistryRebindingOperationEntity {

	@Id
	UUID id;

	@Column(name = "training_project_id", nullable = false)
	UUID projectId;

	@Column(name = "active_binding_revision", nullable = false)
	long activeBindingRevision;

	@Column(name = "candidate_binding_revision", nullable = false)
	long candidateBindingRevision;

	@Column(nullable = false)
	String state;

	@Column(nullable = false)
	int attempts;

	@Column(name = "failure_codes", nullable = false)
	String failureCodes;

	@Column(name = "started_at", nullable = false)
	Instant startedAt;

	@Column(name = "completed_at")
	Instant completedAt;

	@Version
	@Column(name = "persistence_version", nullable = false)
	long persistenceVersion;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "registry_rebinding_artifact",
			joinColumns = @JoinColumn(name = "registry_rebinding_operation_id"))
	@OrderColumn(name = "artifact_position")
	List<RebindingArtifactEmbeddable> artifacts = new ArrayList<>();

	protected RegistryRebindingOperationEntity() {
	}

	static RegistryRebindingOperationEntity create(UUID id, UUID projectId, long activeBindingRevision,
			long candidateBindingRevision, Instant startedAt) {
		var result = new RegistryRebindingOperationEntity();
		result.id = id;
		result.projectId = projectId;
		result.activeBindingRevision = activeBindingRevision;
		result.candidateBindingRevision = candidateBindingRevision;
		result.state = "verifying";
		result.failureCodes = "";
		result.startedAt = startedAt;
		return result;
	}

	void record(List<RebindingArtifact> values, List<String> failures, String state, Instant completedAt) {
		this.attempts++;
		this.artifacts = values.stream()
			.map(RebindingArtifactEmbeddable::from)
			.collect(java.util.stream.Collectors.toCollection(ArrayList::new));
		this.failureCodes = String.join(",", failures);
		this.state = state;
		this.completedAt = completedAt;
	}

	void terminate(String state, Instant completedAt) {
		this.state = state;
		this.completedAt = completedAt;
	}

	RegistryRebindingOperationView view() {
		return new RegistryRebindingOperationView(this.id, this.projectId, this.candidateBindingRevision, this.state,
				this.attempts, this.artifacts.stream().map(RebindingArtifactEmbeddable::domain).toList(),
				this.failureCodes.isEmpty() ? List.of() : List.of(this.failureCodes.split(",")), this.startedAt,
				this.completedAt);
	}

}
