package de.zorro909.skywright.backend.trainingproject;

import de.zorro909.skywright.backend.projectversion.ProjectVersionRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

@Transactional
public class RegistryRebindings {

	private final TrainingProjectRepository repository;

	private final TrainingProjectArtifactReferences references;

	private final ProjectVersionRegistry registry;

	private final TrainingProjectCredentialReadiness credentialReadiness;

	private final Clock clock;

	RegistryRebindings(TrainingProjectRepository repository, TrainingProjectArtifactReferences references,
			ProjectVersionRegistry registry, TrainingProjectCredentialReadiness credentialReadiness) {
		this(repository, references, registry, credentialReadiness, Clock.systemUTC());
	}

	RegistryRebindings(TrainingProjectRepository repository, TrainingProjectArtifactReferences references,
			ProjectVersionRegistry registry, TrainingProjectCredentialReadiness credentialReadiness, Clock clock) {
		this.repository = repository;
		this.references = references;
		this.registry = registry;
		this.credentialReadiness = credentialReadiness;
		this.clock = clock;
	}

	public UUID start(UUID projectId, long expectedRevision, String candidateRepository, RegistryAccessMode accessMode,
			UUID resolverCredentialBindingId, UUID executionCredentialBindingId) {
		TrainingProjectEntity project = project(projectId);
		requireRevision(project, expectedRevision);
		if (this.repository.hasActiveOperation(projectId)) {
			throw new TrainingProjectException("REGISTRY_REBINDING_CONFLICT",
					"A Registry Rebinding Operation is already active.");
		}
		TrainingProjectView view = project.view();
		String canonicalRepository = TrainingProjects.requireRepository(candidateRepository);
		TrainingProjects.requireBindingShape(accessMode, resolverCredentialBindingId, executionCredentialBindingId);
		if (this.repository.repositoryExists(canonicalRepository)) {
			throw new TrainingProjectException("TRAINING_PROJECT_REPOSITORY_CONFLICT",
					"The repository is already bound to a Training Project.");
		}
		RegistryReadiness readiness = readiness(accessMode, resolverCredentialBindingId, executionCredentialBindingId);
		long candidateRevision = view.activeBinding().revision() + 1;
		project.stageCandidate(new RegistryBinding(candidateRevision, canonicalRepository, accessMode,
				resolverCredentialBindingId, executionCredentialBindingId, readiness, "candidate"));
		UUID operationId = UUID.randomUUID();
		this.repository.createOperation(RegistryRebindingOperationEntity.create(operationId, projectId,
				view.activeBinding().revision(), candidateRevision, this.clock.instant()));
		verify(project, operation(operationId));
		return operationId;
	}

	@Transactional(readOnly = true)
	public RegistryRebindingOperationView get(UUID projectId, UUID operationId) {
		RegistryRebindingOperationEntity operation = operation(operationId);
		if (!operation.projectId.equals(projectId)) {
			throw new TrainingProjectException("REGISTRY_REBINDING_NOT_FOUND",
					"The Registry Rebinding Operation does not exist.");
		}
		return operation.view();
	}

	public void retry(UUID projectId, UUID operationId, long expectedRevision) {
		TrainingProjectEntity project = project(projectId);
		requireRevision(project, expectedRevision);
		RegistryRebindingOperationEntity operation = operation(operationId);
		requireProject(operation, projectId);
		if (!("failed".equals(operation.state) || "verifying".equals(operation.state))) {
			throw new TrainingProjectException("REGISTRY_REBINDING_TERMINAL",
					"A terminal Registry Rebinding Operation cannot be retried.");
		}
		verify(project, operation);
	}

	public void abandon(UUID projectId, UUID operationId, long expectedRevision) {
		TrainingProjectEntity project = project(projectId);
		requireRevision(project, expectedRevision);
		RegistryRebindingOperationEntity operation = operation(operationId);
		requireProject(operation, projectId);
		if ("promoted".equals(operation.state) || "abandoned".equals(operation.state)) {
			throw new TrainingProjectException("REGISTRY_REBINDING_TERMINAL",
					"A terminal Registry Rebinding Operation cannot be abandoned.");
		}
		project.abandonCandidate(operation.candidateBindingRevision);
		operation.terminate("abandoned", this.clock.instant());
	}

	private void verify(TrainingProjectEntity project, RegistryRebindingOperationEntity operation) {
		RegistryBinding candidate = project.view()
			.bindingHistory()
			.stream()
			.filter(binding -> binding.revision() == operation.candidateBindingRevision)
			.findFirst()
			.orElseThrow();
		if (readiness(candidate.accessMode(), candidate.resolverCredentialBindingId(),
				candidate.executionCredentialBindingId()) != RegistryReadiness.READY) {
			operation.record(List.of(), List.of("REGISTRY_CREDENTIALS_UNAVAILABLE"), "failed", null);
			return;
		}
		List<RebindingArtifact> checked = check(this.references.referencedArtifacts(project.id),
				candidate.repository());
		List<String> failures = checked.stream()
			.filter(artifact -> !artifact.verified())
			.map(RebindingArtifact::failureCode)
			.distinct()
			.sorted()
			.toList();
		if (!failures.isEmpty()) {
			operation.record(checked, failures, "failed", null);
			return;
		}
		List<RebindingArtifact> finalCheck = check(this.references.referencedArtifacts(project.id),
				candidate.repository());
		List<String> finalFailures = finalCheck.stream()
			.filter(artifact -> !artifact.verified())
			.map(RebindingArtifact::failureCode)
			.distinct()
			.sorted()
			.toList();
		if (!finalFailures.isEmpty()) {
			operation.record(finalCheck, finalFailures, "failed", null);
			return;
		}
		project.promoteCandidate(operation.activeBindingRevision, operation.candidateBindingRevision);
		operation.record(finalCheck, List.of(), "promoted", this.clock.instant());
	}

	private List<RebindingArtifact> check(Set<ReferencedProjectArtifact> references, String candidateRepository) {
		List<RebindingArtifact> checked = new ArrayList<>();
		references.stream()
			.sorted(Comparator.comparing(artifact -> artifact.kind().name() + artifact.digest()))
			.forEach(artifact -> checked.add(check(artifact, candidateRepository)));
		return checked;
	}

	private RebindingArtifact check(ReferencedProjectArtifact artifact, String candidateRepository) {
		try {
			boolean verified = artifact.kind() == ReferencedProjectArtifact.Kind.IMAGE
					? this.registry.imageAvailable(candidateRepository, artifact.digest())
					: this.registry.pullArtifact(candidateRepository, artifact.digest())
						.filter(pulled -> artifact.digest().equals(pulled.manifestDigest()))
						.isPresent();
			return new RebindingArtifact(artifact.kind(), candidateRepository, artifact.digest(), verified,
					verified ? null : "REGISTRY_REBINDING_ARTIFACT_MISSING");
		}
		catch (RuntimeException failure) {
			return new RebindingArtifact(artifact.kind(), candidateRepository, artifact.digest(), false,
					"REGISTRY_REBINDING_REGISTRY_UNAVAILABLE");
		}
	}

	private RegistryReadiness readiness(RegistryAccessMode accessMode, UUID resolver, UUID execution) {
		if (accessMode == RegistryAccessMode.PUBLIC) {
			return RegistryReadiness.READY;
		}
		if (resolver == null || execution == null) {
			return RegistryReadiness.MISSING;
		}
		RegistryReadiness first = this.credentialReadiness.readiness(resolver, "backend-resolver");
		return first == RegistryReadiness.READY ? this.credentialReadiness.readiness(execution, "execution-target-pull")
				: first;
	}

	private TrainingProjectEntity project(UUID id) {
		return this.repository.find(id)
			.orElseThrow(() -> new TrainingProjectException("TRAINING_PROJECT_NOT_FOUND",
					"The Training Project does not exist."));
	}

	private RegistryRebindingOperationEntity operation(UUID id) {
		return this.repository.findOperation(id)
			.orElseThrow(() -> new TrainingProjectException("REGISTRY_REBINDING_NOT_FOUND",
					"The Registry Rebinding Operation does not exist."));
	}

	private static void requireRevision(TrainingProjectEntity project, long expectedRevision) {
		if (project.revision != expectedRevision) {
			throw new TrainingProjectException("TRAINING_PROJECT_REVISION_CONFLICT",
					"The Training Project changed; reload it and retry.");
		}
	}

	private static void requireProject(RegistryRebindingOperationEntity operation, UUID projectId) {
		if (!operation.projectId.equals(projectId)) {
			throw new TrainingProjectException("REGISTRY_REBINDING_NOT_FOUND",
					"The Registry Rebinding Operation does not exist.");
		}
	}

}
