package de.zorro909.skywright.backend.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RunJobAdapterTest {

	private static final UUID RUN = UUID.fromString("00000000-0000-0000-0000-000000000401");

	private final Source source = new Source();

	private final ConcurrentHashMap<UUID, String> durableClaims = new ConcurrentHashMap<>();

	private final List<RetainedSkyPilotFact> retained = new ArrayList<>();

	private final Clock clock = Clock.fixed(Instant.parse("2026-09-06T12:00:00Z"), ZoneOffset.UTC);

	private RunJobAdapter adapter() {
		return new RunJobAdapter(source, (run, fingerprint) -> {
			String prior = durableClaims.putIfAbsent(run, fingerprint);
			return CompletableFuture.completedFuture(prior == null ? LaunchDispatchGate.Decision.FIRST_DISPATCH
					: prior.equals(fingerprint) ? LaunchDispatchGate.Decision.ALREADY_DISPATCHED
							: LaunchDispatchGate.Decision.DEFINITION_CONFLICT);
		}, facts -> {
			retained.addAll(facts);
			return CompletableFuture.completedFuture(null);
		}, clock);
	}

	private static OrchestratorTaskSpecification task() {
		return new OrchestratorTaskSpecification(RunJobAdapter.jobName(RUN), null, "train",
				List.of(new OrchestratorTaskSpecification.Resources("kubernetes/local", "8", "32", "MI300X:1",
						"docker:project@sha256:pin", false)),
				Map.of());
	}

	private OperationOutcome.ManagedJobStatus job(String status, int recoveries) {
		return new OperationOutcome.ManagedJobStatus(4L, RunJobAdapter.jobName(RUN), status, recoveries, 0, 100.0,
				110.0,
				status.equals("SUCCEEDED") || status.startsWith("FAILED") || status.equals("CANCELLED") ? 150.0 : null,
				recoveries > 0 ? 130.0 : null, "sky-source-start", "kubernetes", "local", null, "MI300X:1",
				status.startsWith("FAILED") ? "source failure" : null, "source-cluster");
	}

	@Test
	void concurrentDeliveryClaimsOneLaunchEvenBeforeItsResponseExists() {
		source.submission = new CompletableFuture<>();
		var one = adapter().submit(RUN, task(), null);
		var two = adapter().submit(RUN, task(), null).toCompletableFuture().join();
		assertThat(two).isInstanceOf(RunJobAdapter.Submission.Rediscovered.class);
		assertThat(source.launches).hasValue(1);
		assertThat(one.toCompletableFuture()).isNotDone();
		source.submission
			.complete(OrchestratorResult.accepted(new OrchestratorOperation("ephemeral", OperationKind.SUBMISSION)));
		assertThat(one.toCompletableFuture().join()).isInstanceOf(RunJobAdapter.Submission.Initiated.class);
	}

	@Test
	void parallelWorkersCannotBothLaunch() throws Exception {
		try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
			var barrier = new java.util.concurrent.CyclicBarrier(2);
			java.util.concurrent.Callable<RunJobAdapter.Submission> delivery = () -> {
				barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
				return adapter().submit(RUN, task(), null).toCompletableFuture().join();
			};
			var first = workers.submit(delivery);
			var second = workers.submit(delivery);
			var results = List.of(first.get(5, java.util.concurrent.TimeUnit.SECONDS),
					second.get(5, java.util.concurrent.TimeUnit.SECONDS));
			assertThat(results).filteredOn(r -> r instanceof RunJobAdapter.Submission.Initiated).hasSize(1);
			assertThat(results).filteredOn(r -> r instanceof RunJobAdapter.Submission.Rediscovered).hasSize(1);
			assertThat(source.launches).hasValue(1);
		}
	}

	@Test
	void alternativeResourceTargetsAreRejectedBeforeDispatch() {
		var alternatives = new OrchestratorTaskSpecification(task().name(), null, "train", List.of(
				task().resources().getFirst(),
				new OrchestratorTaskSpecification.Resources("aws", "8", "32", "H100:1", "different-image", false)),
				Map.of());
		assertThat(adapter().submit(RUN, alternatives, null).toCompletableFuture().join())
			.isEqualTo(new RunJobAdapter.Submission.Refused("PINNED_RESOURCE_REQUIRED"));
		assertThat(durableClaims).isEmpty();
		assertThat(source.launches).hasValue(0);
	}

	@Test
	void lostResponseAndRestartNeverRelaunchOnDelayedOrExpiredVisibility() {
		source.submission = CompletableFuture.failedFuture(new IllegalStateException("response lost after launch"));
		assertThat(adapter().submit(RUN, task(), null).toCompletableFuture().join())
			.isInstanceOf(RunJobAdapter.Submission.Refused.class);
		var restarted = adapter();
		for (int i = 0; i < 3; i++) {
			var repeat = (RunJobAdapter.Submission.Rediscovered) restarted.submit(RUN, task(), null)
				.toCompletableFuture()
				.join();
			assertThat(repeat.observation().availability()).isEqualTo(RunJobAdapter.SourceAvailability.MISSING);
		}
		source.jobs = List.of(job("RUNNING", 1));
		var recovered = (RunJobAdapter.Submission.Rediscovered) restarted.submit(RUN, task(), null)
			.toCompletableFuture()
			.join();
		assertThat(recovered.observation().availability()).isEqualTo(RunJobAdapter.SourceAvailability.LIVE);
		assertThat(source.launches).hasValue(1);
		assertThat(retained).extracting(RetainedSkyPilotFact::kind).contains(RetainedSkyPilotFact.Kind.RECOVERY);
		assertThat(recovered.observation().liveJobs().getFirst().jobId()).isEqualTo(4);
	}

	@Test
	void crashAfterDurableClaimBeforeRemoteDispatchSacrificesAvailability() {
		var first = new RunJobAdapter(source, (run, fingerprint) -> {
			durableClaims.put(run, fingerprint);
			return new CompletableFuture<>();
		}, facts -> CompletableFuture.completedFuture(null), clock);
		first.submit(RUN, task(), null);
		source.jobs = List.of();
		var result = (RunJobAdapter.Submission.Rediscovered) adapter().submit(RUN, task(), null)
			.toCompletableFuture()
			.join();
		assertThat(result.observation().availability()).isEqualTo(RunJobAdapter.SourceAvailability.MISSING);
		assertThat(source.launches).hasValue(0);
	}

	@Test
	void rejectedIdentityOrChangedDefinitionNeverDispatches() {
		adapter().submit(RUN, task(), null).toCompletableFuture().join();
		var changed = new OrchestratorTaskSpecification(task().name(), null, "different", task().resources(), Map.of());
		assertThat(adapter().submit(RUN, changed, null).toCompletableFuture().join())
			.isEqualTo(new RunJobAdapter.Submission.Refused("RUN_JOB_DEFINITION_CONFLICT"));
		assertThat(adapter().submit(UUID.randomUUID(), task(), null).toCompletableFuture().join())
			.isEqualTo(new RunJobAdapter.Submission.Refused("RUN_JOB_IDENTITY_MISMATCH"));
		assertThat(source.launches).hasValue(1);
	}

	@Test
	void sourceAbsenceDuplicateJobsAndPartialPagesRemainExplicit() {
		source.jobs = List.of(job("RUNNING", 0), job("SUCCEEDED", 0));
		assertThat(adapter().reconcile(RUN).toCompletableFuture().join().availability())
			.isEqualTo(RunJobAdapter.SourceAvailability.AMBIGUOUS);
		retained.clear();
		source.jobs = List.of(job("SUCCEEDED", 0));
		source.complete = false;
		assertThat(adapter().reconcile(RUN).toCompletableFuture().join().availability())
			.isEqualTo(RunJobAdapter.SourceAvailability.INCOMPLETE);
		assertThat(retained).extracting(RetainedSkyPilotFact::kind).contains(RetainedSkyPilotFact.Kind.TERMINATION);
	}

	@Test
	void retentionKeysUseSourceEventsAndNeverPollTimeOrDerivedLifecycle() {
		source.jobs = List.of(job("FAILED", 2));
		var first = adapter().reconcile(RUN).toCompletableFuture().join();
		var later = new RunJobAdapter(source,
				(r, f) -> CompletableFuture.completedFuture(LaunchDispatchGate.Decision.ALREADY_DISPATCHED),
				facts -> CompletableFuture.completedFuture(null), Clock.offset(clock, java.time.Duration.ofDays(1)));
		var second = later.reconcile(RUN).toCompletableFuture().join();
		assertThat(first.retainedFacts()).extracting(RetainedSkyPilotFact::sourceEventIdentity)
			.containsExactlyElementsOf(
					second.retainedFacts().stream().map(RetainedSkyPilotFact::sourceEventIdentity).toList());
		assertThat(first.retainedFacts()).extracting(RetainedSkyPilotFact::observedAt)
			.doesNotContain(second.retainedFacts().getFirst().observedAt());
		assertThat(first.retainedFacts()).filteredOn(f -> f.kind() == RetainedSkyPilotFact.Kind.TERMINATION)
			.singleElement()
			.satisfies(f -> assertThat(f.payload()).containsEntry("status", "FAILED").doesNotContainKey("lifecycle"));
		assertThat(first.evidenceGaps()).anyMatch(g -> g.startsWith("ONLY_LATEST_RECOVERY"));
	}

	@Test
	void missingIdentifiersAndWriterUncertaintyDoNotInventProof() {
		source.jobs = List.of(new OperationOutcome.ManagedJobStatus(null, task().name(), "RECOVERING", null, null, null,
				null, null, null, null, null, null, null, null, null, null));
		var result = adapter().reconcile(RUN).toCompletableFuture().join();
		assertThat(result.evidenceGaps()).contains("SOURCE_JOB_ID_MISSING", "SOURCE_RECOVERY_COUNT_MISSING");
		assertThat(result.retainedFacts()).isEmpty();
		assertThat(adapter().previousWriter(RUN, UUID.randomUUID()).reason()).contains("do not prove");
	}

	@Test
	void cancellationAndCleanupAcknowledgeRequestsWithoutChangingSourceOutcome() {
		source.jobs = List.of(job("RUNNING", 0));
		var adapter = adapter();
		var cancel = adapter.cancel(RUN).toCompletableFuture().join();
		assertThat(cancel.value().kind()).isEqualTo(OperationKind.CONTROL);
		assertThat(adapter.complete(RUN, cancel.value()).toCompletableFuture().join().outcome())
			.isEqualTo(new OperationOutcome.Controlled(true));
		assertThat(adapter.reconcile(RUN).toCompletableFuture().join().liveJobs().getFirst().status())
			.isEqualTo("RUNNING");
		assertThat(adapter.cleanup(new CleanupRequest("source-cluster")).toCompletableFuture().join().value().kind())
			.isEqualTo(OperationKind.CLEANUP);
	}

	@Test
	void unavailableSourceAndFailedRetentionDoNotReturnStoredLiveStatus() {
		source.unavailable = true;
		assertThat(adapter().reconcile(RUN).toCompletableFuture().join().availability())
			.isEqualTo(RunJobAdapter.SourceAvailability.UNAVAILABLE);
		source.unavailable = false;
		source.jobs = List.of(job("SUCCEEDED", 0));
		var adapter = new RunJobAdapter(source,
				(r, f) -> CompletableFuture.completedFuture(LaunchDispatchGate.Decision.ALREADY_DISPATCHED),
				facts -> CompletableFuture.failedFuture(new IllegalStateException("database down")), clock);
		var result = adapter.reconcile(RUN).toCompletableFuture().join();
		assertThat(result.availability()).isEqualTo(RunJobAdapter.SourceAvailability.RETENTION_UNAVAILABLE);
		assertThat(result.retainedFacts()).isEmpty();
	}

	@Test
	void capacityAndEligibilityFailuresRemainTypedAndTerminal() {
		for (String category : List.of("ResourcesUnavailableError", "NoCloudAccessError", "CommandError",
				"ClusterDoesNotExist")) {
			source.operationFailure = category;
			var result = adapter()
				.complete(RUN, new OrchestratorOperation("expired-or-failed", OperationKind.SUBMISSION))
				.toCompletableFuture()
				.join();
			assertThat(result.failureKind()).isNotNull().isNotEqualTo(RunJobAdapter.OperationFailureKind.UNKNOWN);
			assertThat(source.launches).hasValue(0);
		}
	}

	@Test
	void expiredRequestIsDiscardedAndTerminalJobIsRediscoveredAfterRestart() {
		adapter().submit(RUN, task(), null).toCompletableFuture().join();
		source.operationFailure = "ClientError";
		assertThat(adapter().complete(RUN, new OrchestratorOperation("expired", OperationKind.SUBMISSION))
			.toCompletableFuture()
			.join()
			.outcome()).isInstanceOf(OperationOutcome.Failed.class);
		source.operationFailure = null;
		source.jobs = List.of(job("SUCCEEDED", 0));
		var result = (RunJobAdapter.Submission.Rediscovered) adapter().submit(RUN, task(), null)
			.toCompletableFuture()
			.join();
		assertThat(result.observation().retainedFacts()).extracting(RetainedSkyPilotFact::kind)
			.contains(RetainedSkyPilotFact.Kind.TERMINATION);
		assertThat(source.launches).hasValue(1);
	}

	@Test
	void attemptCorrelationDoesNotInventProviderGeneration() {
		UUID attempt = UUID.randomUUID();
		var correlated = adapter().correlate(new de.zorro909.skywright.backend.runstore.ExecutionAttemptReference(
				RUN.toString(), attempt.toString(), "version"));
		assertThat(correlated.executionAttemptId()).isEqualTo(attempt);
		assertThat(correlated.jobName()).isEqualTo(task().name());
		assertThat(correlated.scope()).contains("RUN_ONLY", "unproven");
	}

	@Test
	void launchFailureWaitsForRetentionAndSurvivesMissingJobVisibility() {
		source.operationFailure = "ResourcesUnavailableError";
		var durable = new CompletableFuture<Void>();
		var adapter = new RunJobAdapter(source,
				(r, f) -> CompletableFuture.completedFuture(LaunchDispatchGate.Decision.FIRST_DISPATCH), facts -> {
					retained.addAll(facts);
					return durable;
				}, clock);
		var completion = adapter.complete(RUN, new OrchestratorOperation("ephemeral", OperationKind.SUBMISSION))
			.toCompletableFuture();
		assertThat(completion).isNotDone();
		durable.complete(null);
		assertThat(completion.join().retentionConfirmed()).isTrue();
		assertThat(retained).singleElement().satisfies(f -> {
			assertThat(f.runId()).isEqualTo(RUN);
			assertThat(f.kind()).isEqualTo(RetainedSkyPilotFact.Kind.SUBMISSION_OPERATION_FAILURE);
			assertThat(f.sourceEventIdentity()).isEqualTo("first-dispatch");
			assertThat(f.payload()).containsEntry("category", "ResourcesUnavailableError");
		});
		source.operationFailure = null;
		assertThat(adapter().reconcile(RUN).toCompletableFuture().join().availability())
			.isEqualTo(RunJobAdapter.SourceAvailability.MISSING);
		assertThat(retained).hasSize(1);
	}

	@Test
	void launchFailureDoesNotAcknowledgeFailedRetention() {
		source.operationFailure = "NoCloudAccessError";
		var adapter = new RunJobAdapter(source,
				(r, f) -> CompletableFuture.completedFuture(LaunchDispatchGate.Decision.FIRST_DISPATCH),
				facts -> CompletableFuture.failedFuture(new IllegalStateException("offline")), clock);
		var result = adapter.complete(RUN, new OrchestratorOperation("ephemeral", OperationKind.SUBMISSION))
			.toCompletableFuture()
			.join();
		assertThat(result.retentionConfirmed()).isFalse();
		assertThat(result.unavailable()).isNotNull();
	}

	@Test
	void everySourceIdentityComponentIsRequiredForDurableFacts() {
		for (var job : List.of(
				new OperationOutcome.ManagedJobStatus(4L, task().name(), "FAILED", 0, null, 100., 110., 150., null,
						"source-generation", null, null, null, null, null, null),
				new OperationOutcome.ManagedJobStatus(4L, task().name(), "FAILED", 0, 0, 100., 110., 150., null, null,
						null, null, null, null, null, null))) {
			source.jobs = List.of(job);
			var result = adapter().reconcile(RUN).toCompletableFuture().join();
			assertThat(result.retainedFacts()).isEmpty();
			assertThat(result.evidenceGaps()).contains("TERMINATION_SOURCE_EVENT_ID_MISSING");
		}
	}

	static class Source implements Orchestrator {

		AtomicInteger launches = new AtomicInteger();

		List<OperationOutcome.ManagedJobStatus> jobs = List.of();

		boolean complete = true, unavailable;

		String operationFailure;

		CompletableFuture<OrchestratorResult<OrchestratorOperation>> submission = CompletableFuture
			.completedFuture(OrchestratorResult.accepted(new OrchestratorOperation("first", OperationKind.SUBMISSION)));

		public CompletionStage<OrchestratorResult<OrchestratorOperation>> submit(OrchestratorTaskSpecification task) {
			launches.incrementAndGet();
			return submission;
		}

		public CompletionStage<OrchestratorResult<OrchestratorOperation>> observe(StatusRequest request) {
			return CompletableFuture.completedFuture(unavailable
					? OrchestratorResult
						.failure(BridgeFailure.unavailable(BridgeFailure.FailureCause.REACHABILITY, "offline"))
					: OrchestratorResult.accepted(new OrchestratorOperation("new-status", OperationKind.STATUS)));
		}

		public CompletionStage<OrchestratorResult<OrchestratorOperation>> control(ControlRequest request) {
			return CompletableFuture.completedFuture(
					OrchestratorResult.accepted(new OrchestratorOperation("cancel", OperationKind.CONTROL)));
		}

		public CompletionStage<OrchestratorResult<OrchestratorOperation>> cleanup(CleanupRequest request) {
			return CompletableFuture.completedFuture(
					OrchestratorResult.accepted(new OrchestratorOperation("cleanup", OperationKind.CLEANUP)));
		}

		public CompletionStage<OrchestratorResult<OperationOutcome>> complete(OrchestratorOperation operation) {
			return CompletableFuture.completedFuture(OrchestratorResult.accepted(operationFailure != null
					? new OperationOutcome.Failed(operationFailure, "source rejected operation")
					: switch (operation.kind()) {
						case STATUS -> new OperationOutcome.Observed(jobs, complete);
						case CONTROL -> new OperationOutcome.Controlled(true);
						case CLEANUP -> new OperationOutcome.Cleaned(true);
						case SUBMISSION -> new OperationOutcome.Submitted(4, null);
					}));
		}

		public SkyPilotAvailability availability() {
			return SkyPilotAvailability.healthy();
		}

		public CompletionStage<SkyPilotAvailability> refreshAvailability() {
			return CompletableFuture.completedFuture(availability());
		}

		public void close() {
		}

	}

}
