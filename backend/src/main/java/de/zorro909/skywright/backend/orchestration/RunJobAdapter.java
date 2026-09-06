package de.zorro909.skywright.backend.orchestration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import de.zorro909.skywright.backend.credential.TrainingCredentials;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Correlates effects without keeping authoritative live status or transient request IDs.
 */
public final class RunJobAdapter {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "FAILED_SETUP", "FAILED_PRECHECKS",
			"FAILED_NO_RESOURCE", "FAILED_CONTROLLER", "CANCELLED");

	private final Orchestrator orchestrator;

	private final LaunchDispatchGate dispatch;

	private final RetainedSkyPilotFacts retention;

	private final Clock clock;

	public RunJobAdapter(Orchestrator orchestrator, LaunchDispatchGate dispatch, RetainedSkyPilotFacts retention,
			Clock clock) {
		this.orchestrator = java.util.Objects.requireNonNull(orchestrator);
		this.dispatch = java.util.Objects.requireNonNull(dispatch);
		this.retention = java.util.Objects.requireNonNull(retention);
		this.clock = java.util.Objects.requireNonNull(clock);
	}

	public static String jobName(UUID runId) {
		return "skywright-" + runId;
	}

	public sealed interface Submission {

		record Initiated(OrchestratorOperation operation) implements Submission {
		}

		record Rediscovered(Reconciliation observation) implements Submission {
		}

		record Refused(String code) implements Submission {
		}

		record Unavailable(BridgeFailure failure) implements Submission {
		}

	}

	public enum SourceAvailability {

		LIVE, MISSING, INCOMPLETE, AMBIGUOUS, UNAVAILABLE, RETENTION_UNAVAILABLE

	}

	public record Reconciliation(UUID runId, SourceAvailability availability,
			List<OperationOutcome.ManagedJobStatus> liveJobs, List<RetainedSkyPilotFact> retainedFacts,
			List<String> evidenceGaps, BridgeFailure failure) {
		public Reconciliation {
			liveJobs = List.copyOf(liveJobs);
			retainedFacts = List.copyOf(retainedFacts);
			evidenceGaps = List.copyOf(evidenceGaps);
		}
	}

	/** Caller may display this uncertainty; it never authorizes the SDK recovery gate. */
	public record PreviousWriterUncertainty(UUID runId, UUID executionAttemptId, String reason) {
	}

	public PreviousWriterUncertainty previousWriter(UUID runId, UUID attemptId) {
		return new PreviousWriterUncertainty(runId, attemptId,
				"SkyPilot visibility and cluster health do not prove process termination or revoked storage write authority");
	}

	/**
	 * Correlation is only at Run/job scope; SkyPilot does not identify SDK attempt UUIDs.
	 */
	public record ExecutionAttemptCorrelation(UUID runId, UUID executionAttemptId, String jobName, String scope) {
	}

	public ExecutionAttemptCorrelation correlate(
			de.zorro909.skywright.backend.runstore.ExecutionAttemptReference attempt) {
		UUID runId = UUID.fromString(attempt.runId());
		return new ExecutionAttemptCorrelation(runId, UUID.fromString(attempt.attemptId()), jobName(runId),
				"RUN_ONLY; provider recovery generation unproven");
	}

	public CompletionStage<Submission> submit(UUID runId, OrchestratorTaskSpecification task,
			TrainingCredentials credentials) {
		if (!task.name().equals(jobName(runId)))
			return CompletableFuture.completedFuture(new Submission.Refused("RUN_JOB_IDENTITY_MISMATCH"));
		if (task.resources().size() != 1)
			return CompletableFuture.completedFuture(new Submission.Refused("PINNED_RESOURCE_REQUIRED"));
		return this.dispatch.claim(runId, fingerprint(task)).<Submission>thenCompose(decision -> switch (decision) {
			case DEFINITION_CONFLICT ->
				CompletableFuture.completedFuture(new Submission.Refused("RUN_JOB_DEFINITION_CONFLICT"));
			case UNAVAILABLE ->
				CompletableFuture.completedFuture(new Submission.Refused("DISPATCH_AUTHORITY_UNAVAILABLE"));
			case ALREADY_DISPATCHED -> reconcile(runId).thenApply(Submission.Rediscovered::new);
			case FIRST_DISPATCH ->
				(credentials == null ? this.orchestrator.submit(task) : this.orchestrator.submit(task, credentials))
					.thenApply(result -> result.failure() == null ? new Submission.Initiated(result.value())
							: new Submission.Unavailable(result.failure()));
		}).exceptionally(failure -> new Submission.Refused("DISPATCH_UNCERTAIN"));
	}

	public enum OperationFailureKind {

		CAPACITY_UNAVAILABLE, TARGET_INELIGIBLE, SOURCE_IDENTIFIER_UNAVAILABLE, APPLICATION_OR_CONTROLLER_FAILURE,
		UNKNOWN

	}

	public record OperationObservation(OperationOutcome outcome, OperationFailureKind failureKind,
			BridgeFailure unavailable, boolean retentionConfirmed) {
	}

	public CompletionStage<OperationObservation> complete(UUID runId, OrchestratorOperation operation) {
		return this.orchestrator.complete(operation).thenApply(result -> {
			if (result.failure() != null)
				return new OperationObservation(null, null, result.failure(), false);
			OperationFailureKind kind = null;
			if (result.value() instanceof OperationOutcome.Failed failed)
				kind = switch (failed.category()) {
					case "ResourcesUnavailableError" -> OperationFailureKind.CAPACITY_UNAVAILABLE;
					case "ResourcesMismatchError", "ProvisionPrechecksError", "NoCloudAccessError",
							"CloudUserIdentityError", "KubernetesValidationError", "NotSupportedError" ->
						OperationFailureKind.TARGET_INELIGIBLE;
					case "ClusterNotUpError", "ClusterDoesNotExist" ->
						OperationFailureKind.SOURCE_IDENTIFIER_UNAVAILABLE;
					case "CommandError", "ManagedJobStatusError" ->
						OperationFailureKind.APPLICATION_OR_CONTROLLER_FAILURE;
					default -> OperationFailureKind.UNKNOWN;
				};
			return new OperationObservation(result.value(), kind, null, false);
		}).thenCompose(observation -> {
			if (operation.kind() != OperationKind.SUBMISSION
					|| !(observation.outcome() instanceof OperationOutcome.Failed failed))
				return CompletableFuture.completedFuture(observation);
			// One launch is authorized for this Run. Its observation is stable across
			// repeated completion reads without retaining the transient request ID.
			var fact = new RetainedSkyPilotFact(runId, RetainedSkyPilotFact.Kind.SUBMISSION_OPERATION_FAILURE,
					"first-dispatch", Map.of("category", failed.category(), "message", failed.message(), "meaning",
							"operation failure observation; job outcome may be unknown"),
					this.clock.instant());
			return this.retention.append(List.of(fact))
				.handle((ignored, failure) -> new OperationObservation(observation.outcome(), observation.failureKind(),
						failure == null ? null : BridgeFailure.unavailable(BridgeFailure.FailureCause.ADAPTER_CONTRACT,
								"Submission failure retention is unavailable"),
						failure == null));
		});
	}

	public CompletionStage<Reconciliation> reconcile(UUID runId) {
		return this.orchestrator.observe(new StatusRequest(List.of(jobName(runId)))).thenCompose(accepted -> {
			if (accepted.failure() != null)
				return CompletableFuture.completedFuture(unavailable(runId, accepted.failure()));
			return this.orchestrator.complete(accepted.value()).thenCompose(result -> {
				if (result.failure() != null)
					return CompletableFuture.completedFuture(unavailable(runId, result.failure()));
				if (!(result.value() instanceof OperationOutcome.Observed observed))
					return CompletableFuture.completedFuture(unavailable(runId, BridgeFailure.unavailable(
							BridgeFailure.FailureCause.ADAPTER_CONTRACT, "SkyPilot observation is unavailable")));
				return retain(runId, observed);
			});
		})
			.exceptionally(failure -> unavailable(runId, BridgeFailure
				.unavailable(BridgeFailure.FailureCause.ADAPTER_CONTRACT, "SkyPilot reconciliation is unavailable")));
	}

	public CompletionStage<OrchestratorResult<OrchestratorOperation>> cancel(UUID runId) {
		return this.orchestrator.control(new ControlRequest(jobName(runId), ControlRequest.Action.CANCEL));
	}

	/**
	 * Cleanup requires a currently source-observed cluster name, never a guessed
	 * Run-derived cluster.
	 */
	public CompletionStage<OrchestratorResult<OrchestratorOperation>> cleanup(CleanupRequest request) {
		return this.orchestrator.cleanup(request);
	}

	private Reconciliation unavailable(UUID runId, BridgeFailure failure) {
		return new Reconciliation(runId, SourceAvailability.UNAVAILABLE, List.of(), List.of(),
				List.of("SOURCE_UNAVAILABLE"), failure);
	}

	private CompletionStage<Reconciliation> retain(UUID runId, OperationOutcome.Observed observed) {
		var jobs = observed.jobs().stream().filter(job -> jobName(runId).equals(job.jobName())).toList();
		var facts = new ArrayList<RetainedSkyPilotFact>();
		var gaps = new ArrayList<String>();
		SourceAvailability availability = !observed.complete() ? SourceAvailability.INCOMPLETE : jobs.size() > 1
				? SourceAvailability.AMBIGUOUS : jobs.isEmpty() ? SourceAvailability.MISSING : SourceAvailability.LIVE;
		if (!observed.complete())
			gaps.add("SOURCE_LOOKUP_INCOMPLETE");
		if (jobs.size() > 1)
			gaps.add("MULTIPLE_SOURCE_JOBS");
		if (jobs.isEmpty())
			return CompletableFuture
				.completedFuture(
						new Reconciliation(runId, availability, jobs, List.of(),
								List.of("ABSENCE_DOES_NOT_AUTHORIZE_LAUNCH",
										observed.complete() ? "SOURCE_JOB_MISSING" : "SOURCE_LOOKUP_INCOMPLETE"),
								null));
		for (var job : jobs) {
			if (job.jobId() == null)
				gaps.add("SOURCE_JOB_ID_MISSING");
			gaps.add("SOURCE_JOB_ID_IS_DATABASE_SCOPED; database epoch unavailable");
			Instant now = this.clock.instant();
			add(facts, gaps, runId, job, RetainedSkyPilotFact.Kind.SUBMISSION, job.submittedAt(), Map.of(), now);
			if (job.startedAt() != null)
				add(facts, gaps, runId, job, RetainedSkyPilotFact.Kind.EXECUTION_STARTED, job.startedAt(), Map.of(),
						now);
			if (job.cloud() != null || job.resources() != null) {
				var payload = new LinkedHashMap<String, String>();
				put(payload, "cloud", job.cloud());
				put(payload, "clusterName", job.clusterName());
				put(payload, "region", job.region());
				put(payload, "zone", job.zone());
				put(payload, "resources", job.resources());
				add(facts, gaps, runId, job, RetainedSkyPilotFact.Kind.INFRASTRUCTURE,
						job.lastRecoveredAt() != null ? job.lastRecoveredAt() : job.startedAt(), payload, now);
			}
			if (job.recoveryCount() == null)
				gaps.add("SOURCE_RECOVERY_COUNT_MISSING");
			else if (job.recoveryCount() > 0) {
				add(facts, gaps, runId, job, RetainedSkyPilotFact.Kind.RECOVERY, job.lastRecoveredAt(),
						Map.of("recoveryCount", job.recoveryCount().toString(), "meaning",
								"orchestrator recovery; termination cause unproven"),
						now);
				if (job.recoveryCount() > 1)
					gaps.add("ONLY_LATEST_RECOVERY_EVENT_OBSERVED; earlier events require retained evidence");
			}
			if (TERMINAL.contains(job.status())) {
				var payload = new LinkedHashMap<String, String>();
				payload.put("status", job.status());
				put(payload, "failureReason", job.failureReason());
				add(facts, gaps, runId, job, RetainedSkyPilotFact.Kind.TERMINATION, job.endedAt(), payload, now);
			}
		}
		return this.retention.append(facts)
			.handle((ignored, failure) -> new Reconciliation(runId,
					failure == null ? availability : SourceAvailability.RETENTION_UNAVAILABLE, jobs,
					failure == null ? facts : List.of(), gaps, null));
	}

	private static void put(Map<String, String> payload, String key, String value) {
		if (value != null)
			payload.put(key, value);
	}

	private static void add(List<RetainedSkyPilotFact> facts, List<String> gaps, UUID runId,
			OperationOutcome.ManagedJobStatus job, RetainedSkyPilotFact.Kind kind, Double sourceTime,
			Map<String, String> data, Instant observedAt) {
		if (sourceTime == null || !Double.isFinite(sourceTime) || sourceTime < 0 || job.jobId() == null
				|| job.taskId() == null || job.runTimestamp() == null || job.runTimestamp().isBlank()) {
			gaps.add(kind + "_SOURCE_EVENT_ID_MISSING");
			return;
		}
		var payload = new LinkedHashMap<>(data);
		payload.put("sourceTime", sourceTime.toString());
		payload.put("jobName", job.jobName());
		String sourceId = job.jobId() + ":" + String.valueOf(job.taskId()) + ":" + String.valueOf(job.runTimestamp())
				+ ":" + sourceTime;
		facts.add(new RetainedSkyPilotFact(runId, kind, sourceId, payload, observedAt));
	}

	private static String fingerprint(OrchestratorTaskSpecification task) {
		try {
			return "sha256:" + HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256")
					.digest(canonical(JSON.valueToTree(task)).toString().getBytes(StandardCharsets.UTF_8)));
		}
		catch (java.security.NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	private static JsonNode canonical(JsonNode value) {
		if (value.isObject()) {
			var result = JSON.createObjectNode();
			value.propertyNames().stream().sorted().forEach(key -> result.set(key, canonical(value.path(key))));
			return result;
		}
		if (value.isArray()) {
			var result = JSON.createArrayNode();
			value.forEach(item -> result.add(canonical(item)));
			return result;
		}
		return value;
	}

}
