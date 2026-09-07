package de.zorro909.skywright.backend.runlifecycle;

import de.zorro909.skywright.backend.orchestration.RetainedSkyPilotFact;
import de.zorro909.skywright.backend.orchestration.RunJobAdapter;
import de.zorro909.skywright.backend.runstore.RunProcessEvidence;
import de.zorro909.skywright.backend.runstore.RunStoreIntegrityException;
import de.zorro909.skywright.backend.runsubmission.AcceptedRun;
import de.zorro909.skywright.backend.runsubmission.RunAcceptanceStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** All public and internal lifecycle reads take the same live, read-through path. */
@Service
public final class RunLifecycleReads {

	private final RunAcceptanceStore runs;

	private final RunJobAdapter jobs;

	private final RunProcessReads process;

	private final RunControlDecisions controls;

	private final RunLifecycleDerivation derivation = new RunLifecycleDerivation();

	// A bounded, non-authoritative side channel only. Restart deliberately loses it.
	private final LinkedHashMap<UUID, RunLifecycleView.LastSeen> lastSeen = new LinkedHashMap<>(16, 0.75f, true);

	RunLifecycleReads(RunAcceptanceStore runs, RunJobAdapter jobs, RunProcessReads process,
			ObjectProvider<RunControlDecisions> controls) {
		this.runs = runs;
		this.jobs = jobs;
		this.process = process;
		this.controls = controls.getIfAvailable(() -> ignored -> List.of());
	}

	public record Read(AcceptedRun run, RunLifecycleView lifecycle) {
	}

	public Read read(UUID runId) {
		return read(runs.get(runId));
	}

	public Read read(AcceptedRun run) {
		var gaps = new ArrayList<String>();
		Instant skyPilotReadAt = Instant.now();
		RunJobAdapter.Reconciliation source;
		try {
			source = jobs.reconcile(run.runId()).toCompletableFuture().get(5, TimeUnit.SECONDS);
		}
		catch (Exception failure) {
			if (failure instanceof InterruptedException)
				Thread.currentThread().interrupt();
			source = new RunJobAdapter.Reconciliation(run.runId(), RunJobAdapter.SourceAvailability.UNAVAILABLE,
					List.of(), List.of(), List.of("SOURCE_UNAVAILABLE"), null);
		}
		gaps.addAll(source.evidenceGaps());
		List<RetainedSkyPilotFact> retained;
		try {
			retained = runs.retainedFacts(run.runId());
		}
		catch (RuntimeException failure) {
			retained = List.of();
			gaps.add("RETAINED_FACTS_UNAVAILABLE");
		}
		Instant runStoreReadAt = Instant.now();
		RunProcessEvidence evidence = null;
		String processAvailability = "live";
		try {
			evidence = process.read(run);
		}
		catch (RunStoreIntegrityException failure) {
			processAvailability = "invalid";
			gaps.add(failure.getMessage());
		}
		catch (RuntimeException failure) {
			processAvailability = "unavailable";
		}
		List<RunControlDecisions.Decision> decisions;
		try {
			decisions = controls.read(run.runId());
		}
		catch (RuntimeException failure) {
			decisions = List.of();
			gaps.add("CONTROL_DECISIONS_UNAVAILABLE");
		}
		Instant fetchedAt = Instant.now();
		var result = derivation.derive(new RunLifecycleDerivation.Evidence(source.availability(), source.liveJobs(),
				source.retainedFacts(), retained, evidence, decisions, fetchedAt, gaps));
		var seen = remember(run.runId(), result.state(), skyPilotReadAt, fetchedAt);
		return new Read(run,
				new RunLifecycleView(wire(result.state()), result.terminalLatched(), result.cause(),
						wire(source.availability()), processAvailability, fetchedAt, skyPilotReadAt, runStoreReadAt,
						seen, result.gaps(), result.facts(), result.conflicts(), decisions));
	}

	public record Page(List<Read> items, UUID nextCursor) {
		public Page {
			items = List.copyOf(items);
		}
	}

	public Page page(UUID after, int limit) {
		if (limit < 1 || limit > 50)
			throw new IllegalArgumentException("Run page limit must be 1..50");
		var ids = runs.page(after, limit + 1);
		var items = new ArrayList<Read>();
		long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
		long retainedCharacters = 0;
		for (int i = 0; i < Math.min(limit, ids.size()); i++) {
			if (!items.isEmpty() && (System.nanoTime() >= deadline || retainedCharacters >= 2 * 1024 * 1024))
				break;
			var item = read(ids.get(i));
			items.add(item);
			retainedCharacters += item.lifecycle()
				.facts()
				.stream()
				.mapToLong(f -> 200L + f.sourceEventIdentity().length()
						+ f.payload()
							.entrySet()
							.stream()
							.mapToLong(e -> e.getKey().length() + e.getValue().length())
							.sum())
				.sum();
		}
		return new Page(items, items.size() < ids.size() ? items.getLast().run().runId() : null);
	}

	private synchronized RunLifecycleView.LastSeen remember(UUID runId, RunLifecycle state, Instant observedAt,
			Instant at) {
		if (state != null) {
			lastSeen.put(runId, new RunLifecycleView.LastSeen(wire(state), observedAt, 0));
			if (lastSeen.size() > 1024)
				lastSeen.remove(lastSeen.keySet().iterator().next());
			return null;
		}
		var seen = lastSeen.get(runId);
		return seen == null ? null : new RunLifecycleView.LastSeen(seen.state(), seen.observedAt(),
				Math.max(0, Duration.between(seen.observedAt(), at).toMillis()));
	}

	private static String wire(Enum<?> value) {
		return value == null ? null : value.name().toLowerCase(Locale.ROOT).replace('_', '-');
	}

}
