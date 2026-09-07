package de.zorro909.skywright.backend.runlifecycle;

import de.zorro909.skywright.backend.runstore.*;
import de.zorro909.skywright.backend.runsubmission.RunAcceptanceStore;
import de.zorro909.skywright.backend.targetstorage.TargetStorageResolver;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Independent read-through of the current, small progress projection. */
@Component
public final class RunProgressReads {

	private final RunAcceptanceStore runs;

	private final TargetStorageResolver storages;

	public RunProgressReads(RunAcceptanceStore runs, TargetStorageResolver storages) {
		this.runs = runs;
		this.storages = storages;
	}

	public record Observation(String availability, Instant fetchedAt, ProgressRecord record) {
	}

	public Observation read(UUID runId) {
		var run = runs.get(runId);
		String project = run.definition().value().at("/trainingProjectVersion/projectIdentity").asText();
		try {
			var target = storages.resolveRunOutputRead(runs.currentStorage(runId), project, runId.toString());
			long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
			var control = new RunStoreOperationControl(Duration.ofSeconds(5),
					() -> Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline);
			try (var objects = new S3RunStoreObjectStore(target, control)) {
				return read(new RunStoreProtocol(project, runId.toString()), objects);
			}
		}
		catch (RuntimeException failure) {
			return new Observation("unavailable", Instant.now(), null);
		}
	}

	static Observation read(RunStoreProtocol protocol, RunStoreObjectStore objects) {
		try {
			var progress = new RunStoreAccess(protocol, objects).readProgress();
			return new Observation("available", Instant.now(), progress);
		}
		catch (RunStoreIntegrityException failure) {
			return new Observation(failure.getMessage().startsWith("RUN_STORE_MISSING_OBJECT") ? "absent" : "invalid",
					Instant.now(), null);
		}
		catch (RuntimeException failure) {
			return new Observation("unavailable", Instant.now(), null);
		}
	}

}
