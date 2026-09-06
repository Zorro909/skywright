package de.zorro909.skywright.backend.runsubmission;

import de.zorro909.skywright.backend.trainingproject.ReferencedProjectArtifact;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import de.zorro909.skywright.backend.rundefinition.RunDefinition;
import de.zorro909.skywright.backend.orchestration.OrchestratorTaskSpecification;
import tools.jackson.databind.json.JsonMapper;

@Entity
@Table(name = "run_record", schema = "skywright")
class RunRecordEntity {

	@Id
	UUID id;

	@Column(name = "submission_id", nullable = false, updatable = false)
	UUID submissionId;

	@Column(name = "principal_identity", nullable = false, updatable = false)
	String principalIdentity;

	@Column(name = "request_digest", nullable = false, updatable = false)
	String requestDigest;

	@Column(name = "accepted_at", nullable = false, updatable = false)
	Instant acceptedAt;

	@Column(name = "definition_json", nullable = false, updatable = false, columnDefinition = "text")
	String definition;

	@Column(name = "task_json", nullable = false, updatable = false, columnDefinition = "text")
	String task;

	@Column(name = "task_fingerprint", nullable = false, updatable = false)
	String taskFingerprint;

	@Column(name = "project_identity", nullable = false, updatable = false)
	String projectIdentity;

	@Column(name = "artifact_references_json", nullable = false, updatable = false, columnDefinition = "text")
	String artifactReferences;

	protected RunRecordEntity() {
	}

	RunRecordEntity(AcceptedRun run) {
		id = run.runId();
		projectIdentity = run.definition().value().at("/trainingProjectVersion/projectIdentity").asText();
		artifactReferences = JsonMapper.builder().build().writeValueAsString(run.artifacts());
		taskFingerprint = de.zorro909.skywright.backend.orchestration.RunJobAdapter.taskFingerprint(run.task());
		submissionId = run.submissionId();
		principalIdentity = "built-in";
		requestDigest = run.requestDigest();
		acceptedAt = run.acceptedAt();
		definition = run.definition().encode();
		task = JsonMapper.builder().build().writeValueAsString(run.task());
	}

	AcceptedRun view() {
		return new AcceptedRun(id, submissionId, requestDigest, acceptedAt, RunDefinition.decode(definition),
				JsonMapper.builder().build().readValue(task, OrchestratorTaskSpecification.class),
				JsonMapper.builder()
					.build()
					.readValue(artifactReferences,
							new tools.jackson.core.type.TypeReference<java.util.Set<ReferencedProjectArtifact>>() {
							}));
	}

}
