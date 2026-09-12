package de.zorro909.skywright.backend.runstore;

import de.zorro909.skywright.backend.runsubmission.RunAcceptanceStore;
import de.zorro909.skywright.backend.targetstorage.TargetStorageResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Bounded output reads using each Run's current location and backend credential. */
@Service
public final class RunOutputReads {

	private final RunAcceptanceStore runs;

	private final TargetStorageResolver storages;

	private final Semaphore reads = new Semaphore(2);

	public RunOutputReads(RunAcceptanceStore runs, TargetStorageResolver storages) {
		this.runs = runs;
		this.storages = storages;
	}

	public RunStoreOutputPage list(UUID runId, String kind, String cursor) {
		var outputKind = switch (kind) {
			case "artifact" -> RunStoreOutputKind.ARTIFACT;
			case "sample" -> RunStoreOutputKind.SAMPLE;
			default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid output kind");
		};
		return read(runId, access -> access.listOutputs(outputKind, 20, cursor));
	}

	public byte[] download(UUID runId, String key) {
		var run = runs.get(runId);
		String project = run.definition().value().at("/trainingProjectVersion/projectIdentity").asText();
		String prefix = new RunStoreProtocol(project, runId.toString()).runPrefix();
		if (!(key.startsWith(prefix + "artifacts/") || key.startsWith(prefix + "samples/")))
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid output reference");
		return read(runId, access -> {
			try (var staged = access.stageDownload(key, Path.of(System.getProperty("java.io.tmpdir")),
					8 * 1024 * 1024)) {
				return Files.readAllBytes(staged.path());
			}
			catch (java.io.IOException failure) {
				throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Output download unavailable");
			}
		});
	}

	private <T> T read(UUID runId, Function<RunStoreAccess, T> operation) {
		if (!reads.tryAcquire())
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Output read capacity is full");
		try {
			var run = runs.get(runId);
			String project = run.definition().value().at("/trainingProjectVersion/projectIdentity").asText();
			try {
				var target = storages.resolveRunOutputRead(runs.currentStorage(runId), project, runId.toString());
				long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
				var control = new RunStoreOperationControl(Duration.ofSeconds(5),
						() -> Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline);
				try (var objects = new S3RunStoreObjectStore(target, control)) {
					return operation
						.apply(new RunStoreAccess(new RunStoreProtocol(project, runId.toString()), objects));
				}
			}
			catch (RuntimeException failure) {
				throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Run outputs unavailable");
			}
		}
		finally {
			reads.release();
		}
	}

}
