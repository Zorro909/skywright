package de.zorro909.skywright.backend.runlog;

import de.zorro909.skywright.backend.runlifecycle.RunLifecycleDerivation;
import de.zorro909.skywright.backend.runsubmission.RunAcceptanceStore;
import de.zorro909.skywright.backend.targetstorage.TargetStorageResolver;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Captures through current Run storage and closes publication with an immutable manifest.
 */
@Service
public final class RunLogArchives {

	private final RunAcceptanceStore runs;

	private final TargetStorageResolver storages;

	private final RunLogCaptureStore captures;

	private final RunLogSource source;

	private final Clock clock;

	RunLogArchives(RunAcceptanceStore runs, TargetStorageResolver storages, RunLogCaptureStore captures,
			RunLogSource source, Clock clock) {
		this.runs = runs;
		this.storages = storages;
		this.captures = captures;
		this.source = source;
		this.clock = clock;
	}

	public void reconcile(UUID runId) {
		var claim = captures.claim(runId);
		if (claim == null)
			return;
		var run = runs.get(runId);
		var definition = run.definition().value();
		String project = definition.at("/trainingProjectVersion/projectIdentity").asText();
		String version = definition.at("/trainingProjectVersion/manifestArtifactDigest").asText();
		boolean prevented = runs.dispatchPrevented(runId);
		boolean terminal = prevented || new RunLifecycleDerivation().terminalRetention(runs.retainedFacts(runId));
		var target = storages.resolveRunOutputRead(runs.currentStorage(runId), project, runId.toString());
		captures.publish(claim, checkpoint -> {
			try (var objects = new S3ArchiveObjects(target)) {
				return capture(runId, version, checkpoint, new ArchiveJournal(objects, runId, version), terminal,
						prevented);
			}
		});
	}

	RunLogCaptureStore.Saved capture(UUID runId, String version, RunLogCheckpoint checkpoint, ArchiveJournal journal,
			boolean terminal, boolean prevented) {
		var published = journal.publication();
		if (published != null)
			return saved(published);
		var archive = new RunLogArchive(runId, version);
		if (terminal)
			checkpoint = checkpoint.terminal(clock.instant());
		boolean expired = checkpoint.terminalAt() != null
				&& !clock.instant().isBefore(checkpoint.terminalAt().plusSeconds(300));
		for (String stream : java.util.List.of("task", "controller")) {
			var prior = checkpoint.stream(stream);
			if (prior.ready())
				continue;
			var recovered = journal.recover(stream, prior);
			if (!recovered.equals(prior)) {
				checkpoint = checkpoint.with(stream, recovered);
				continue;
			}
			if (expired) {
				checkpoint = checkpoint.with(stream, RunLogArchive.partial(prior, "FINALIZATION_DEADLINE"));
				continue;
			}
			if (prevented) {
				var empty = new RunLogArchive.Page("dispatch-prevented", 0, new byte[0], "{}", true, true, null);
				checkpoint = checkpoint.with(stream,
						journal.append(archive.append(stream, prior, empty, true, clock.instant(), journal::confirms)));
				continue;
			}
			try {
				var page = source.fetch(runId, stream, prior);
				checkpoint = checkpoint.sourceFailure(stream, null);
				// An empty read without writer closure supplies no finalization evidence.
				if (terminal && page.bytes().length == 0 && !page.sealed()) {
					checkpoint = checkpoint.with(stream, archive.unavailable(prior, true, "WRITER_UNCONFIRMED"));
					continue;
				}
				checkpoint = checkpoint.with(stream, journal
					.append(archive.append(stream, prior, page, terminal, clock.instant(), journal::confirms)));
			}
			catch (RunLogSource.Unavailable unavailable) {
				checkpoint = checkpoint.sourceFailure(stream, unavailable.getMessage());
				checkpoint = checkpoint.with(stream, archive.unavailable(prior, terminal, unavailable.getMessage()));
			}
		}
		if (checkpoint.ready()) {
			checkpoint = journal.verifyMarkers(checkpoint);
			if (!checkpoint.markersVerified() && expired) {
				checkpoint = checkpoint
					.with("task", RunLogArchive.partial(checkpoint.task(), "MARKER_VERIFICATION_DEADLINE"))
					.markers(checkpoint.markerAfter(), true);
			}
			if (checkpoint.markersVerified())
				return saved(journal.finish(checkpoint, clock.instant()));
		}
		return new RunLogCaptureStore.Saved(checkpoint, null, null);
	}

	private static RunLogCaptureStore.Saved saved(ArchiveJournal.Published published) {
		return new RunLogCaptureStore.Saved(published.manifest().checkpoint(), published.digest(),
				published.manifest().publishedAt());
	}

}
