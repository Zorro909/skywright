package de.zorro909.skywright.backend.runlifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import de.zorro909.skywright.backend.orchestration.*;
import de.zorro909.skywright.backend.runstore.RunProcessEvidence;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RunLifecycleDerivationTest {

	private static final UUID RUN = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

	private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");

	private final RunLifecycleDerivation reducer = new RunLifecycleDerivation();

	@ParameterizedTest
	@CsvSource({ "PENDING,WAITING", "SUBMITTED,WAITING", "STARTING,WAITING", "RUNNING,RUNNING",
			"RECOVERING,INTERRUPTED", "CANCELLING,RUNNING", "SUCCEEDED,FINISHED", "CANCELLED,CANCELLED",
			"FAILED,FAILED", "FAILED_SETUP,FAILED", "FAILED_PRECHECKS,FAILED", "FAILED_NO_RESOURCE,FAILED",
			"FAILED_CONTROLLER,FAILED" })
	void sourceTruthTableWithAbsentReports(String source, RunLifecycle expected) {
		var result = derive(RunJobAdapter.SourceAvailability.LIVE, source, process(null), List.of(), List.of());
		assertThat(result.state()).isEqualTo(expected);
		assertThat(result.cause()).isNull();
		assertThat(result.gaps()).contains("TERMINATION_REPORT_ABSENT; cause unproven");
	}

	@ParameterizedTest
	@CsvSource({ "completed,FINISHED", "cancelled,CANCELLED", "policy_stopped,CANCELLED", "contract_violation,FAILED",
			"training_project_failure,FAILED", "skywright_failure,FAILED" })
	void processFinalizationWinsEvenBeforeSourceExitOrDuringOutage(String cause, RunLifecycle state) {
		for (var source : List.of(RunJobAdapter.SourceAvailability.LIVE,
				RunJobAdapter.SourceAvailability.UNAVAILABLE)) {
			var input = new RunLifecycleDerivation.Evidence(source, List.of(job("RUNNING")), List.of(), List.of(),
					process(cause),
					List.of(new RunControlDecisions.Decision(UUID.randomUUID(),
							RunControlDecisions.Kind.CANCELLATION_REQUEST, NOW),
							new RunControlDecisions.Decision(UUID.randomUUID(), RunControlDecisions.Kind.CEILING_STOP,
									NOW)),
					NOW, List.of());
			assertThat(reducer.derive(input).state()).isEqualTo(state);
		}
	}

	@Test
	void dispatchPreventionProvesNoWriterButStartupRefusalAloneDoesNotProveEarlierWriterStopped() {
		var request = new RunControlDecisions.Decision(UUID.randomUUID(), RunControlDecisions.Kind.CANCELLATION_REQUEST,
				NOW);
		var prevented = new RunControlDecisions.Decision(request.id(), request.kind(), NOW, true);
		var absent = new RunLifecycleDerivation.Evidence(RunJobAdapter.SourceAvailability.UNAVAILABLE, List.of(),
				List.of(), List.of(), null, List.of(request), NOW, List.of());
		assertThat(reducer.derive(absent).state()).isNull();
		var proof = new RunLifecycleDerivation.Evidence(RunJobAdapter.SourceAvailability.UNAVAILABLE, List.of(),
				List.of(), List.of(), null, List.of(prevented), NOW, List.of());
		assertThat(reducer.derive(proof).state()).isEqualTo(RunLifecycle.CANCELLED);
		assertThat(reducer.derive(proof).cause()).isNull();
		var refusal = new RunProcessEvidence.StopRefusal(request.id().toString(), "cancellation", NOW);
		var history = new RunProcessEvidence(process(null).attempts(), "head", 0, null, refusal);
		assertThat(derive(RunJobAdapter.SourceAvailability.LIVE, "RUNNING", history, List.of(), List.of()).state())
			.isEqualTo(RunLifecycle.RUNNING);
		assertThat(derive(RunJobAdapter.SourceAvailability.UNAVAILABLE, null, history, List.of(), List.of()).state())
			.isNull();
		assertThat(derive(RunJobAdapter.SourceAvailability.LIVE, "FAILED", history, List.of(), List.of()).state())
			.isEqualTo(RunLifecycle.CANCELLED);
		for (String cause : List.of("completed", "training_project_failure", "policy_stopped")) {
			var terminal = new RunProcessEvidence(process(cause).attempts(), "head", 0, null, refusal);
			assertThat(derive(RunJobAdapter.SourceAvailability.LIVE, "FAILED", terminal, List.of(), List.of()).cause())
				.isEqualTo(cause);
		}
	}

	@Test
	void requestsNeverAdvanceLifecycleAndExhaustionHasNoInventedProcessCause() {
		var input = new RunLifecycleDerivation.Evidence(RunJobAdapter.SourceAvailability.LIVE, List.of(job("RUNNING")),
				List.of(), List.of(), process(null), List.of(new RunControlDecisions.Decision(UUID.randomUUID(),
						RunControlDecisions.Kind.CEILING_STOP, NOW)),
				NOW, List.of());
		assertThat(reducer.derive(input).state()).isEqualTo(RunLifecycle.RUNNING);
		var exhaustion = new RunProcessEvidence(process("interrupted").attempts(), "head", 3, NOW);
		var result = derive(RunJobAdapter.SourceAvailability.LIVE, "RUNNING", exhaustion, List.of(), List.of());
		assertThat(result.state()).isEqualTo(RunLifecycle.FAILED);
		assertThat(result.cause()).isNull();
	}

	@Test
	void sourceLossDoesNotTurnLastAttemptOrInterruptionIntoAuthoritativeLiveState() {
		for (String cause : new String[] { null, "interrupted" }) {
			assertThat(derive(RunJobAdapter.SourceAvailability.UNAVAILABLE, null, process(cause), List.of(), List.of())
				.state()).isNull();
		}
		assertThat(
				derive(RunJobAdapter.SourceAvailability.LIVE, "RUNNING", process("interrupted"), List.of(), List.of())
					.state())
			.isEqualTo(RunLifecycle.INTERRUPTED);
		assertThat(
				derive(RunJobAdapter.SourceAvailability.LIVE, "RUNNING", process(null), List.of(), List.of()).state())
			.isEqualTo(RunLifecycle.RUNNING);
	}

	@Test
	void terminalFactsSurviveSourcePurgeAndOutageButLiveSourceWins() {
		var finished = fact("SUCCEEDED", NOW);
		for (var availability : List.of(RunJobAdapter.SourceAvailability.MISSING,
				RunJobAdapter.SourceAvailability.UNAVAILABLE)) {
			var result = derive(availability, null, process(null), List.of(), List.of(finished));
			assertThat(result.state()).isEqualTo(RunLifecycle.FINISHED);
			assertThat(result.terminalLatched()).isTrue();
		}
		var live = derive(RunJobAdapter.SourceAvailability.LIVE, "RUNNING", process(null), List.of(),
				List.of(finished));
		assertThat(live.state()).isEqualTo(RunLifecycle.RUNNING);
		assertThat(live.terminalLatched()).isFalse();
		assertThat(live.gaps()).contains("LIVE_SOURCE_SUPERSEDES_RETAINED_TERMINAL");
	}

	@Test
	void conflictingNaturalKeysSelectLatestObservationDeterministicallyAndExposeAlternatives() {
		var first = fact("FAILED", NOW.minusSeconds(20));
		var second = fact("SUCCEEDED", NOW.minusSeconds(10));
		var repeated = fact("FAILED", NOW);
		var result = derive(RunJobAdapter.SourceAvailability.MISSING, null, process(null), List.of(),
				List.of(second, first, repeated));
		assertThat(result.state()).isEqualTo(RunLifecycle.FAILED);
		assertThat(result.facts()).containsExactly(repeated);
		assertThat(result.conflicts()).singleElement()
			.satisfies(conflict -> assertThat(conflict.alternatives()).containsExactly(second));
		var live = derive(RunJobAdapter.SourceAvailability.LIVE, "SUCCEEDED", process(null), List.of(first),
				List.of(second, repeated));
		assertThat(live.facts()).containsExactly(first);
		var tieOne = fact("FAILED", NOW);
		var tieTwo = fact("SUCCEEDED", NOW);
		assertThat(derive(RunJobAdapter.SourceAvailability.MISSING, null, process(null), List.of(),
				List.of(tieOne, tieTwo)))
			.isEqualTo(derive(RunJobAdapter.SourceAvailability.MISSING, null, process(null), List.of(),
					List.of(tieTwo, tieOne)));
	}

	@Test
	void missingProcessSourceAndAmbiguousOrUnknownSkyPilotValuesStayExplicit() {
		assertThat(derive(RunJobAdapter.SourceAvailability.LIVE, "SUCCEEDED", null, List.of(), List.of()).state())
			.isNull();
		for (var unavailable : List.of(RunJobAdapter.SourceAvailability.INCOMPLETE,
				RunJobAdapter.SourceAvailability.AMBIGUOUS, RunJobAdapter.SourceAvailability.RETENTION_UNAVAILABLE)) {
			assertThat(derive(unavailable, "RUNNING", process(null), List.of(), List.of()).state()).isNull();
		}
		assertThat(
				derive(RunJobAdapter.SourceAvailability.LIVE, "NEW_UNKNOWN_STATE", process(null), List.of(), List.of())
					.state())
			.isNull();
		assertThat(derive(RunJobAdapter.SourceAvailability.MISSING, null,
				new RunProcessEvidence(List.of(), null, 0, null), List.of(), List.of())
			.state()).isEqualTo(RunLifecycle.WAITING);
	}

	@Test
	void submissionOperationFailureRemainsEvidenceWithoutInventingJobOutcome() {
		var failure = new RetainedSkyPilotFact(RUN, RetainedSkyPilotFact.Kind.SUBMISSION_OPERATION_FAILURE,
				"first-dispatch", Map.of("category", "ResourcesUnavailableError"), NOW);
		var result = derive(RunJobAdapter.SourceAvailability.MISSING, null,
				new RunProcessEvidence(List.of(), null, 0, null), List.of(), List.of(failure));
		assertThat(result.state()).isEqualTo(RunLifecycle.WAITING);
		assertThat(result.terminalLatched()).isFalse();
		assertThat(result.facts()).containsExactly(failure);
		assertThat(result.gaps()).contains("SUBMISSION_OPERATION_FAILED; job outcome remains source-derived");
		assertThat(derive(RunJobAdapter.SourceAvailability.LIVE, "FAILED_NO_RESOURCE", process(null), List.of(),
				List.of(failure))
			.state()).isEqualTo(RunLifecycle.FAILED);
	}

	@Test
	void ambiguousOrSupersededTerminalObservationsDoNotLatchRetention() {
		var terminal = fact("SUCCEEDED", NOW);
		var ambiguous = new RetainedSkyPilotFact(RUN, terminal.kind(), terminal.sourceEventIdentity(),
				terminal.payload(), NOW.plusSeconds(1), false);
		assertThat(reducer.terminalRetention(List.of(terminal))).isTrue();
		assertThat(reducer.terminalRetention(List.of(terminal, ambiguous))).isFalse();
		var laterNonterminal = new RetainedSkyPilotFact(RUN, RetainedSkyPilotFact.Kind.EXECUTION_STARTED,
				"42:0:generation:110.0", Map.of("sourceTime", "110.0"), NOW.plusSeconds(1));
		assertThat(reducer.terminalRetention(List.of(terminal, laterNonterminal))).isFalse();
		var disambiguated = fact("FAILED", NOW.plusSeconds(2));
		assertThat(reducer.terminalRetention(List.of(terminal, ambiguous, disambiguated))).isTrue();
	}

	private RunLifecycleDerivation.Result derive(RunJobAdapter.SourceAvailability availability, String status,
			RunProcessEvidence process, List<RetainedSkyPilotFact> live, List<RetainedSkyPilotFact> retained) {
		return reducer.derive(new RunLifecycleDerivation.Evidence(availability,
				status == null ? List.of() : List.of(job(status)), live, retained, process, List.of(), NOW, List.of()));
	}

	private static RunProcessEvidence process(String cause) {
		return new RunProcessEvidence(
				List.of(new RunProcessEvidence.Attempt(RUN.toString(), cause, 1L, 1L, "checkpoint")), "head", 0, null);
	}

	private static RetainedSkyPilotFact fact(String status, Instant at) {
		return new RetainedSkyPilotFact(RUN, RetainedSkyPilotFact.Kind.TERMINATION, "42:0:generation:150.0",
				Map.of("status", status, "sourceTime", "150.0"), at);
	}

	private static OperationOutcome.ManagedJobStatus job(String state) {
		return new OperationOutcome.ManagedJobStatus(42L, "skywright-" + RUN, state, 0, 0, 100., 110., 150., null,
				"generation", "kubernetes", "local", null, "MI300X:1", null, "worker");
	}

}
