package de.zorro909.skywright.backend.runsubmission;

import de.zorro909.skywright.backend.boundary.generated.api.RunsApi;
import de.zorro909.skywright.backend.boundary.generated.model.AcceptedLocalRun;
import de.zorro909.skywright.backend.boundary.generated.model.CreateLocalRun;
import java.net.URI;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class LocalRunHttpAdapter implements RunsApi {

	private final LocalRunSubmissions submissions;

	private final de.zorro909.skywright.backend.runlifecycle.RunLifecycleReads lifecycle;

	LocalRunHttpAdapter(LocalRunSubmissions submissions,
			de.zorro909.skywright.backend.runlifecycle.RunLifecycleReads lifecycle) {
		this.submissions = submissions;
		this.lifecycle = lifecycle;
	}

	@Override
	public ResponseEntity<AcceptedLocalRun> createLocalRun(CreateLocalRun request) {
		var result = submissions.create(new LocalRunRequest(request.getSubmissionId(), request.getTrainingProjectId(),
				request.getManifestArtifactDigest(), request.getDatasetDefinitionId(),
				request.getPreferredDatasetCopyId(), request.getExecutionStorageId(), request.getTarget(),
				request.getGpuCount(), request.getConfiguration(), request.getMaximumRecoveryDebt()));
		return ResponseEntity.accepted()
			.location(URI.create("/api/v1/runs/" + result.run().runId()))
			.body(response(result));
	}

	@Override
	public ResponseEntity<AcceptedLocalRun> getLocalRun(UUID runId) {
		return ResponseEntity.ok(response(submissions.get(runId)));
	}

	@Override
	public ResponseEntity<de.zorro909.skywright.backend.boundary.generated.model.RunPage> listRuns(UUID after,
			Integer limit) {
		var page = lifecycle.page(after, limit == null ? 20 : limit);
		return ResponseEntity.ok(new de.zorro909.skywright.backend.boundary.generated.model.RunPage(
				page.items().stream().map(LocalRunSubmissions::observed).map(this::response).toList())
			.nextCursor(page.nextCursor()));
	}

	private AcceptedLocalRun response(LocalRunSubmissions.Result result) {
		var run = result.run();
		java.util.Map<String, Object> definition = JsonMapper.builder()
			.build()
			.convertValue(run.definition().value(),
					new tools.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {
					});
		return new AcceptedLocalRun(run.runId(), run.submissionId(), run.acceptedAt().atOffset(ZoneOffset.UTC),
				AcceptedLocalRun.AcceptedIntentEnum.SUBMIT, AcceptedLocalRun.HandoffEnum.fromValue(result.handoff()),
				AcceptedLocalRun.SourceAvailabilityEnum.fromValue(result.sourceAvailability()), definition,
				result.evidenceGaps())
			.lifecycle(result.lifecycle() == null ? null
					: JsonMapper.builder()
						.build()
						.convertValue(result.lifecycle(),
								de.zorro909.skywright.backend.boundary.generated.model.RunLifecycleObservation.class));
	}

}
