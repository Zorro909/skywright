package de.zorro909.skywright.backend.trainingproject;

import de.zorro909.skywright.backend.boundary.generated.api.TrainingProjectsApi;
import de.zorro909.skywright.backend.boundary.generated.model.CreateTrainingProject;
import de.zorro909.skywright.backend.boundary.generated.model.RegistryRebindingArtifact;
import de.zorro909.skywright.backend.boundary.generated.model.RegistryRebindingOperation;
import de.zorro909.skywright.backend.boundary.generated.model.RenameTrainingProject;
import de.zorro909.skywright.backend.boundary.generated.model.ReplaceTrainingProjectRegistryCredentials;
import de.zorro909.skywright.backend.boundary.generated.model.RevisionCheckedOperation;
import de.zorro909.skywright.backend.boundary.generated.model.StartRegistryRebinding;
import de.zorro909.skywright.backend.boundary.generated.model.TrainingProject;
import de.zorro909.skywright.backend.boundary.generated.model.TrainingProjectRegistryBinding;
import de.zorro909.skywright.backend.boundary.generated.model.TrainingProjectVersionAssessment;
import de.zorro909.skywright.backend.boundary.generated.model.TrainingProjectVersionDiscovery;
import de.zorro909.skywright.backend.boundary.generated.model.TrainingProjectVersionFailure;
import de.zorro909.skywright.backend.boundary.generated.model.TrainingProjectVersionReference;
import de.zorro909.skywright.backend.boundary.generated.model.VerifiedTrainingProjectVersion;
import java.net.URI;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TrainingProjectHttpAdapter implements TrainingProjectsApi {

	private final TrainingProjects projects;

	private final RegistryRebindings rebindings;

	TrainingProjectHttpAdapter(TrainingProjects projects, RegistryRebindings rebindings) {
		this.projects = projects;
		this.rebindings = rebindings;
	}

	@Override
	public ResponseEntity<TrainingProject> createTrainingProject(CreateTrainingProject request) {
		var registry = request.getRegistry();
		UUID id = this.projects.create(request.getDisplayName(), registry.getRepository(),
				accessMode(registry.getAccessMode()), registry.getResolverCredentialBindingId(),
				registry.getExecutionCredentialBindingId());
		return ResponseEntity.created(URI.create("/api/v1/training-projects/" + id))
			.body(project(this.projects.get(id)));
	}

	@Override
	public ResponseEntity<List<TrainingProject>> listTrainingProjects() {
		return ResponseEntity.ok(this.projects.list().stream().map(this::project).toList());
	}

	@Override
	public ResponseEntity<TrainingProject> getTrainingProject(UUID projectId) {
		return ResponseEntity.ok(project(this.projects.get(projectId)));
	}

	@Override
	public ResponseEntity<TrainingProject> renameTrainingProject(UUID projectId, RenameTrainingProject request) {
		this.projects.rename(projectId, request.getExpectedRevision(), request.getDisplayName());
		return ResponseEntity.ok(project(this.projects.get(projectId)));
	}

	@Override
	public ResponseEntity<TrainingProject> replaceTrainingProjectRegistryCredentials(UUID projectId,
			ReplaceTrainingProjectRegistryCredentials request) {
		this.projects.replaceCredentials(projectId, request.getExpectedRevision(),
				request.getResolverCredentialBindingId(), request.getExecutionCredentialBindingId());
		return ResponseEntity.ok(project(this.projects.get(projectId)));
	}

	@Override
	public ResponseEntity<TrainingProjectVersionDiscovery> listTrainingProjectVersions(UUID projectId) {
		var discovery = this.projects.discoverVersions(projectId);
		return ResponseEntity.ok(new TrainingProjectVersionDiscovery(discovery.registryAvailable(),
				discovery.observedAt().atOffset(ZoneOffset.UTC),
				discovery.versions()
					.stream()
					.map(reference -> new TrainingProjectVersionReference(reference.versionLabel(),
							reference.manifestArtifactDigest()))
					.toList(),
				discovery.errors().stream().map(this::failure).toList()));
	}

	@Override
	public ResponseEntity<TrainingProjectVersionAssessment> assessTrainingProjectVersion(UUID projectId,
			String manifestDigest) {
		var assessment = this.projects.assessVersion(projectId, manifestDigest);
		VerifiedTrainingProjectVersion version = assessment.version() == null ? null
				: new VerifiedTrainingProjectVersion(UUID.fromString(assessment.version().projectIdentity()),
						assessment.version().versionLabel(), assessment.version().manifestArtifactDigest(),
						assessment.version().sourceRevision(), assessment.version().pipeline(),
						assessment.version().images(), assessment.version().environmentProfiles(),
						assessment.version().configurationContractDigest(),
						assessment.version().metricContractDigest());
		return ResponseEntity.ok(new TrainingProjectVersionAssessment(assessment.runnable(),
				assessment.assessedAt().atOffset(ZoneOffset.UTC), version,
				assessment.errors().stream().map(this::failure).toList()));
	}

	private TrainingProjectVersionFailure failure(
			de.zorro909.skywright.backend.projectversion.ProjectVersionFailure value) {
		return new TrainingProjectVersionFailure(value.code(), value.pointer());
	}

	@Override
	public ResponseEntity<RegistryRebindingOperation> startRegistryRebinding(UUID projectId,
			StartRegistryRebinding request) {
		var candidate = request.getCandidate();
		UUID operationId = this.rebindings.start(projectId, request.getExpectedRevision(), candidate.getRepository(),
				accessMode(candidate.getAccessMode()), candidate.getResolverCredentialBindingId(),
				candidate.getExecutionCredentialBindingId());
		return ResponseEntity
			.created(URI.create("/api/v1/training-projects/" + projectId + "/registry-rebindings/" + operationId))
			.body(rebinding(this.rebindings.get(projectId, operationId)));
	}

	@Override
	public ResponseEntity<RegistryRebindingOperation> getRegistryRebinding(UUID projectId, UUID operationId) {
		return ResponseEntity.ok(rebinding(this.rebindings.get(projectId, operationId)));
	}

	@Override
	public ResponseEntity<RegistryRebindingOperation> retryRegistryRebinding(UUID projectId, UUID operationId,
			RevisionCheckedOperation request) {
		this.rebindings.retry(projectId, operationId, request.getExpectedRevision());
		return ResponseEntity.ok(rebinding(this.rebindings.get(projectId, operationId)));
	}

	@Override
	public ResponseEntity<RegistryRebindingOperation> abandonRegistryRebinding(UUID projectId, UUID operationId,
			RevisionCheckedOperation request) {
		this.rebindings.abandon(projectId, operationId, request.getExpectedRevision());
		return ResponseEntity.ok(rebinding(this.rebindings.get(projectId, operationId)));
	}

	private RegistryRebindingOperation rebinding(RegistryRebindingOperationView value) {
		return new RegistryRebindingOperation(value.id(), value.projectId(), value.candidateBindingRevision(),
				RegistryRebindingOperation.StateEnum.fromValue(value.state()), value.attempts(),
				value.artifacts().stream().map(this::artifact).toList(), value.failureCodes(),
				value.startedAt().atOffset(ZoneOffset.UTC),
				value.completedAt() == null ? null : value.completedAt().atOffset(ZoneOffset.UTC));
	}

	private RegistryRebindingArtifact artifact(RebindingArtifact value) {
		return new RegistryRebindingArtifact(
				RegistryRebindingArtifact.KindEnum
					.fromValue(value.kind().name().toLowerCase(Locale.ROOT).replace('_', '-')),
				value.repository(), value.digest(), value.verified(), value.failureCode());
	}

	private static RegistryAccessMode accessMode(
			de.zorro909.skywright.backend.boundary.generated.model.RegistryAccessMode value) {
		return value == null ? RegistryAccessMode.PRIVATE
				: RegistryAccessMode.valueOf(value.getValue().toUpperCase(Locale.ROOT));
	}

	private TrainingProject project(TrainingProjectView value) {
		return new TrainingProject(value.id(), value.displayName(), value.revision(), binding(value.activeBinding()),
				value.bindingHistory().stream().map(this::binding).toList());
	}

	private TrainingProjectRegistryBinding binding(RegistryBinding value) {
		return new TrainingProjectRegistryBinding(value.revision(), value.repository(),
				de.zorro909.skywright.backend.boundary.generated.model.RegistryAccessMode
					.fromValue(value.accessMode().name().toLowerCase(Locale.ROOT)),
				value.resolverCredentialBindingId(), value.executionCredentialBindingId(),
				de.zorro909.skywright.backend.boundary.generated.model.RegistryReadiness
					.fromValue(value.readiness().name().toLowerCase(Locale.ROOT)),
				TrainingProjectRegistryBinding.StateEnum.fromValue(value.state()));
	}

}
