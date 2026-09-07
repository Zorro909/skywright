package de.zorro909.skywright.backend.runsubmission;

import de.zorro909.skywright.backend.orchestration.Orchestrator;
import de.zorro909.skywright.backend.orchestration.RunJobAdapter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class LocalRunSubmissions {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final RunAcceptanceStore store;

	private final LocalRunAdmission admission;

	private final Orchestrator orchestrator;

	private final RunJobAdapter jobs;

	private final de.zorro909.skywright.backend.runlifecycle.RunLifecycleReads lifecycle;

	LocalRunSubmissions(RunAcceptanceStore store, LocalRunAdmission admission, Orchestrator orchestrator,
			RunJobAdapter jobs, de.zorro909.skywright.backend.runlifecycle.RunLifecycleReads lifecycle) {
		this.store = store;
		this.admission = admission;
		this.orchestrator = orchestrator;
		this.jobs = jobs;
		this.lifecycle = lifecycle;
	}

	public record Result(AcceptedRun run, String handoff, String sourceAvailability, List<String> evidenceGaps,
			de.zorro909.skywright.backend.runlifecycle.RunLifecycleView lifecycle) {
		public Result(AcceptedRun run, String handoff, String sourceAvailability, List<String> evidenceGaps) {
			this(run, handoff, sourceAvailability, evidenceGaps, null);
		}

		public Result {
			evidenceGaps = List.copyOf(evidenceGaps);
		}
	}

	public Result create(LocalRunRequest request) {
		String digest = digest(request);
		var previous = store.bySubmission(request.submissionId());
		if (previous.isPresent())
			return replay(previous.get(), digest);
		try {
			if (!orchestrator.refreshAvailability().toCompletableFuture().get(5, TimeUnit.SECONDS).available())
				throw new RunSubmissionException("RUN_ADMISSION_UNAVAILABLE", 503);
		}
		catch (RunSubmissionException failure) {
			throw failure;
		}
		catch (Exception failure) {
			if (failure instanceof InterruptedException)
				Thread.currentThread().interrupt();
			throw new RunSubmissionException("RUN_ADMISSION_UNAVAILABLE", 503);
		}
		RunAcceptanceStore.Creation created;
		try {
			created = store.accept(request, digest, admission);
		}
		catch (RuntimeException failure) {
			var winner = store.bySubmission(request.submissionId());
			if (winner.isPresent())
				return replay(winner.get(), digest);
			throw failure;
		}
		var run = created.run();
		boolean releaseDeferred = false;
		try {
			var delivery = jobs.submit(run.runId(), run.task(), created.prepared().credentials());
			delivery.whenComplete((result, failure) -> created.prepared().close());
			releaseDeferred = true;
			// Retain completion even when acknowledgement arrives after the HTTP wait.
			var result = delivery.thenApply(submission -> {
				if (submission instanceof RunJobAdapter.Submission.Initiated initiated)
					jobs.complete(run.runId(), initiated.operation());
				return submission;
			}).toCompletableFuture().get(5, TimeUnit.SECONDS);
			if (result instanceof RunJobAdapter.Submission.Initiated) {
				return new Result(run, "source-accepted", "not-observed", List.of());
			}
			if (result instanceof RunJobAdapter.Submission.Rediscovered rediscovered)
				return observed(run, rediscovered.observation());
		}
		catch (Exception failure) {
			if (!releaseDeferred)
				created.prepared().close();
			if (failure instanceof InterruptedException)
				Thread.currentThread().interrupt();
		}
		return new Result(run, "uncertain", "not-observed", List.of("HANDOFF_UNCERTAIN"));
	}

	public Result get(java.util.UUID runId) {
		return observe(store.get(runId));
	}

	private Result replay(AcceptedRun run, String digest) {
		if (!run.requestDigest().equals(digest))
			throw new RunSubmissionException("RUN_SUBMISSION_IDENTITY_CONFLICT", 409);
		return observe(run);
	}

	private Result observe(AcceptedRun run) {
		return observed(lifecycle.read(run));
	}

	static Result observed(de.zorro909.skywright.backend.runlifecycle.RunLifecycleReads.Read read) {
		var view = read.lifecycle();
		return new Result(read.run(), view.sourceAvailability().equals("live") ? "source-observed" : "uncertain",
				view.sourceAvailability(), view.evidenceGaps(), view);
	}

	private static Result observed(AcceptedRun run, RunJobAdapter.Reconciliation result) {
		return new Result(run,
				result.availability() == RunJobAdapter.SourceAvailability.LIVE ? "source-observed" : "uncertain",
				result.availability().name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'),
				result.evidenceGaps());
	}

	static String digest(LocalRunRequest request) {
		try {
			byte[] bytes = canonical(JSON.valueToTree(request)).toString().getBytes(StandardCharsets.UTF_8);
			if (bytes.length > 1024 * 1024)
				throw new RunSubmissionException("RUN_SUBMISSION_TOO_LARGE", 422);
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (RunSubmissionException failure) {
			throw failure;
		}
		catch (Exception failure) {
			throw new RunSubmissionException("RUN_SUBMISSION_INVALID", 422);
		}
	}

	private static JsonNode canonical(JsonNode value) {
		if (value.isObject()) {
			var result = JSON.createObjectNode();
			value.propertyNames().stream().sorted().forEach(key -> result.set(key, canonical(value.get(key))));
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
