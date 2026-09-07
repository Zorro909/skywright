package de.zorro909.skywright.backend.runlifecycle;

import de.zorro909.skywright.backend.runstore.*;
import de.zorro909.skywright.backend.runsubmission.AcceptedRun;
import de.zorro909.skywright.backend.runsubmission.RunAcceptanceStore;
import de.zorro909.skywright.backend.targetstorage.TargetStorageResolver;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
final class S3RunProcessReads implements RunProcessReads {

	private final TargetStorageResolver storages;

	private final RunAcceptanceStore runs;

	S3RunProcessReads(TargetStorageResolver storages, RunAcceptanceStore runs) {
		this.storages = storages;
		this.runs = runs;
	}

	@Override
	public RunProcessEvidence read(AcceptedRun run) {
		var definition = run.definition().value();
		String projectId = definition.at("/trainingProjectVersion/projectIdentity").asText();
		String version = definition.at("/trainingProjectVersion/manifestArtifactDigest").asText();
		var target = storages.resolveRunOutputRead(runs.currentStorage(run.runId()), projectId, run.runId().toString());
		long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
		var control = new RunStoreOperationControl(Duration.ofSeconds(5),
				() -> Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline);
		try (var objects = new S3RunStoreObjectStore(target, control)) {
			return new RunStoreLifecycle(new RunStoreProtocol(projectId, run.runId().toString()), objects).read(version,
					definition.at("/executionPolicy/maximumRecoveryDebt").asInt());
		}
	}

}
