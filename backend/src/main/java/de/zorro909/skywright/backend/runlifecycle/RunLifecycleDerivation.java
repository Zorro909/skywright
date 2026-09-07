package de.zorro909.skywright.backend.runlifecycle;

import de.zorro909.skywright.backend.orchestration.OperationOutcome.ManagedJobStatus;
import de.zorro909.skywright.backend.orchestration.RetainedSkyPilotFact;
import de.zorro909.skywright.backend.orchestration.RunJobAdapter.SourceAvailability;
import de.zorro909.skywright.backend.runstore.RunProcessEvidence;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Pure provenance join. A corrected mapping also corrects historical reads. */
public final class RunLifecycleDerivation {

	private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder()
		.build();

	public record Evidence(SourceAvailability availability, List<ManagedJobStatus> liveJobs,
			List<RetainedSkyPilotFact> liveFacts, List<RetainedSkyPilotFact> retainedFacts, RunProcessEvidence process,
			List<RunControlDecisions.Decision> decisions, Instant fetchedAt, List<String> gaps) {
		public Evidence {
			liveJobs = List.copyOf(liveJobs);
			liveFacts = List.copyOf(liveFacts);
			retainedFacts = List.copyOf(retainedFacts);
			decisions = List.copyOf(decisions);
			gaps = List.copyOf(gaps);
		}
	}

	public record Conflict(String kind, String sourceEventIdentity, RetainedSkyPilotFact selected,
			List<RetainedSkyPilotFact> alternatives) {
		public Conflict {
			alternatives = List.copyOf(alternatives);
		}
	}

	public record Result(RunLifecycle state, boolean terminalLatched, String cause, List<RetainedSkyPilotFact> facts,
			List<Conflict> conflicts, List<String> gaps) {
		public Result {
			facts = List.copyOf(facts);
			conflicts = List.copyOf(conflicts);
			gaps = List.copyOf(gaps);
		}
	}

	public Result derive(Evidence evidence) {
		var groups = new TreeMap<String, List<RetainedSkyPilotFact>>();
		for (var fact : evidence.retainedFacts())
			groups.computeIfAbsent(key(fact), ignored -> new ArrayList<>()).add(fact);
		for (var fact : evidence.liveFacts())
			groups.computeIfAbsent(key(fact), ignored -> new ArrayList<>()).add(fact);
		var live = new LinkedHashMap<String, RetainedSkyPilotFact>();
		evidence.liveFacts().forEach(f -> live.merge(key(f), f, RunLifecycleDerivation::latest));
		var selected = new ArrayList<RetainedSkyPilotFact>();
		var conflicts = new ArrayList<Conflict>();
		for (var entry : groups.entrySet()) {
			var winner = live.getOrDefault(entry.getKey(), entry.getValue().stream().max(ORDER).orElseThrow());
			selected.add(winner);
			var variants = new TreeMap<String, RetainedSkyPilotFact>();
			entry.getValue().forEach(f -> variants.merge(payload(f), f, RunLifecycleDerivation::latest));
			if (variants.size() > 1)
				conflicts.add(new Conflict(winner.kind().name(), winner.sourceEventIdentity(), winner,
						variants.values().stream().filter(f -> !f.payload().equals(winner.payload())).toList()));
		}
		var gaps = new ArrayList<>(evidence.gaps());
		if (selected.stream().anyMatch(f -> f.kind() == RetainedSkyPilotFact.Kind.SUBMISSION_OPERATION_FAILURE))
			gaps.add("SUBMISSION_OPERATION_FAILED; job outcome remains source-derived");
		var latestObservation = selected.stream()
			.filter(f -> f.kind() != RetainedSkyPilotFact.Kind.SUBMISSION_OPERATION_FAILURE)
			.max(ORDER)
			.orElse(null);
		var terminalFact = selected.stream()
			.filter(f -> f.kind() == RetainedSkyPilotFact.Kind.TERMINATION && f.completeUniqueObservation())
			.filter(f -> sourceState(f.payload().get("status"), true) != null
					&& sourceState(f.payload().get("status"), true).terminal())
			.filter(f -> latestObservation != null && latestObservation.completeUniqueObservation()
					&& f.observedAt().equals(latestObservation.observedAt()))
			.max(ORDER)
			.orElse(null);
		if (evidence.decisions().stream().anyMatch(RunControlDecisions.Decision::dispatchPrevented)) {
			gaps.add("DISPATCH_PREVENTED; no launch was authorized");
			return new Result(RunLifecycle.CANCELLED, true, null, selected, conflicts, gaps);
		}
		if (evidence.process() == null) {
			gaps.add("RUN_STORE_EVIDENCE_UNAVAILABLE");
			return new Result(null, false, null, selected, conflicts, gaps);
		}
		var process = evidence.process();
		var attempt = process.latestAttempt();
		String cause = attempt == null ? null : attempt.cause();
		RunLifecycle state;
		if (process.exhaustedAt() != null) {
			state = RunLifecycle.FAILED;
			// Exhaustion refuses a prospective process; it invents no process cause.
			cause = null;
		}
		else if (cause != null && !cause.equals("interrupted")) {
			state = switch (cause) {
				case "completed" -> RunLifecycle.FINISHED;
				case "cancelled", "policy_stopped" -> RunLifecycle.CANCELLED;
				default -> RunLifecycle.FAILED;
			};
		}
		else if (evidence.availability() == SourceAvailability.LIVE && evidence.liveJobs().size() == 1) {
			var job = evidence.liveJobs().getFirst();
			state = sourceState(job.status(), job.startedAt() != null);
			if (state == null)
				gaps.add("SOURCE_STATUS_UNRECOGNIZED");
			if (terminalFact != null && (state == null || !state.terminal()))
				gaps.add("LIVE_SOURCE_SUPERSEDES_RETAINED_TERMINAL");
			if (state == RunLifecycle.RUNNING && "interrupted".equals(cause)) {
				state = RunLifecycle.INTERRUPTED;
				gaps.add("LATEST_ATTEMPT_FINALIZED_INTERRUPTION; newer admission not observed");
			}
		}
		else if ((evidence.availability() == SourceAvailability.MISSING
				|| evidence.availability() == SourceAvailability.UNAVAILABLE) && terminalFact != null) {
			state = sourceState(terminalFact.payload().get("status"), true);
			if (state != null && !state.terminal())
				state = null;
		}
		else if (evidence.availability() == SourceAvailability.MISSING && attempt == null) {
			state = RunLifecycle.WAITING;
			gaps.add("ACCEPTED_SUBMISSION; remote handoff remains uncertain");
		}
		else {
			state = null;
		}
		if (process.stopRefusal() != null) {
			gaps.add("STARTUP_REFUSED_BY_STOP_REQUEST:" + process.stopRefusal().commandId());
			// A refused prospective startup does not prove an earlier writer stopped.
			// It explains a source-confirmed terminal result, never an in-flight one.
			if (state != null && state.terminal() && process.exhaustedAt() == null
					&& (cause == null || cause.equals("interrupted"))) {
				state = RunLifecycle.CANCELLED;
				cause = null;
			}
		}
		if (attempt != null && cause == null)
			gaps.add("TERMINATION_REPORT_ABSENT; cause unproven");
		boolean latched = state != null && state.terminal() && terminalFact != null;
		return new Result(state, latched, cause, selected, conflicts, gaps.stream().distinct().toList());
	}

	public boolean terminalRetention(List<RetainedSkyPilotFact> facts) {
		return derive(new Evidence(SourceAvailability.MISSING, List.of(), List.of(), facts,
				new RunProcessEvidence(List.of(), null, 0, null), List.of(), Instant.EPOCH, List.of()))
			.terminalLatched();
	}

	private static RunLifecycle sourceState(String status, boolean started) {
		if (status == null)
			return null;
		return switch (status) {
			case "PENDING", "SUBMITTED", "STARTING" -> RunLifecycle.WAITING;
			case "RUNNING" -> RunLifecycle.RUNNING;
			case "RECOVERING" -> RunLifecycle.INTERRUPTED;
			case "CANCELLING" -> started ? RunLifecycle.RUNNING : RunLifecycle.WAITING;
			case "SUCCEEDED" -> RunLifecycle.FINISHED;
			case "CANCELLED" -> RunLifecycle.CANCELLED;
			case "FAILED", "FAILED_SETUP", "FAILED_PRECHECKS", "FAILED_NO_RESOURCE", "FAILED_CONTROLLER" ->
				RunLifecycle.FAILED;
			default -> null;
		};
	}

	private static final Comparator<RetainedSkyPilotFact> ORDER = Comparator.comparing(RetainedSkyPilotFact::observedAt)
		.thenComparing(f -> !f.completeUniqueObservation())
		.thenComparing(RunLifecycleDerivation::payload)
		.thenComparing(RetainedSkyPilotFact::sourceEventIdentity);

	private static String key(RetainedSkyPilotFact fact) {
		return fact.kind().name() + ":" + fact.sourceEventIdentity();
	}

	private static String payload(RetainedSkyPilotFact fact) {
		return JSON.writeValueAsString(new TreeMap<>(fact.payload()));
	}

	private static RetainedSkyPilotFact latest(RetainedSkyPilotFact one, RetainedSkyPilotFact two) {
		return ORDER.compare(one, two) >= 0 ? one : two;
	}

}
