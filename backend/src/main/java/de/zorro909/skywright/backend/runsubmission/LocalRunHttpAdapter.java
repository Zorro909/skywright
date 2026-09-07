package de.zorro909.skywright.backend.runsubmission;

import de.zorro909.skywright.backend.boundary.generated.api.RunsApi;
import de.zorro909.skywright.backend.boundary.generated.model.AcceptedLocalRun;
import de.zorro909.skywright.backend.boundary.generated.model.CreateLocalRun;
import de.zorro909.skywright.backend.boundary.generated.model.RunLifecycleObservation;
import de.zorro909.skywright.backend.boundary.generated.model.LastSeenRunLifecycle;
import de.zorro909.skywright.backend.boundary.generated.model.RetainedRunSourceFact;
import de.zorro909.skywright.backend.boundary.generated.model.RunSourceConflict;
import de.zorro909.skywright.backend.boundary.generated.model.RunControlDecisionEvidence;
import de.zorro909.skywright.backend.runlifecycle.RunLifecycleView;
import de.zorro909.skywright.backend.orchestration.RetainedSkyPilotFact;
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
			.lifecycle(result.lifecycle() == null ? null : lifecycle(result.lifecycle()));
	}

	private RunLifecycleObservation lifecycle(RunLifecycleView view) {
		var result = new RunLifecycleObservation()
			.state(view.state() == null ? null : RunLifecycleObservation.StateEnum.fromValue(view.state()))
			.cause(view.cause())
			.terminalLatched(view.terminalLatched())
			.sourceAvailability(view.sourceAvailability())
			.processAvailability(RunLifecycleObservation.ProcessAvailabilityEnum.fromValue(view.processAvailability()))
			.fetchedAt(view.fetchedAt().atOffset(ZoneOffset.UTC))
			.skyPilotReadAt(view.skyPilotReadAt().atOffset(ZoneOffset.UTC))
			.runStoreReadAt(view.runStoreReadAt().atOffset(ZoneOffset.UTC))
			.evidenceGaps(view.evidenceGaps())
			.facts(view.facts().stream().map(this::fact).toList())
			.conflicts(view.conflicts()
				.stream()
				.map(c -> new RunSourceConflict(c.kind(), c.sourceEventIdentity(), fact(c.selected()),
						c.alternatives().stream().map(this::fact).toList()))
				.toList())
			.controlDecisions(view.controlDecisions()
				.stream()
				.map(d -> new RunControlDecisionEvidence(d.id(),
						RunControlDecisionEvidence.KindEnum.fromValue(d.kind().name()),
						d.decidedAt().atOffset(ZoneOffset.UTC)))
				.toList());
		if (view.lastSeen() != null) {
			var seen = view.lastSeen();
			result.lastSeen(new LastSeenRunLifecycle(seen.state(), seen.observedAt().atOffset(ZoneOffset.UTC),
					seen.ageMillis()));
		}
		return result;
	}

	private RetainedRunSourceFact fact(RetainedSkyPilotFact fact) {
		return new RetainedRunSourceFact().runId(fact.runId())
			.kind(fact.kind().name())
			.sourceEventIdentity(fact.sourceEventIdentity())
			.payload(fact.payload())
			.observedAt(fact.observedAt().atOffset(ZoneOffset.UTC))
			.completeUniqueObservation(fact.completeUniqueObservation());
	}

}
