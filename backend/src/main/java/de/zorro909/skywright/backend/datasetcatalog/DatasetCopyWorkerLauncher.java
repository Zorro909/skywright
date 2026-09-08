package de.zorro909.skywright.backend.datasetcatalog;

import de.zorro909.skywright.backend.targetstorage.TargetStorageResolver;
import de.zorro909.skywright.backend.worker.WorkerProcessCommand;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import tools.jackson.databind.json.JsonMapper;

/**
 * Owns local worker custody; catalogue commits are made by the maintenance dispatcher.
 */
final class DatasetCopyWorkerLauncher {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final TargetStorageResolver targets;

	private final DatasetCopyWorkerProjections projections;

	private final Duration timeout;

	private final Set<Process> active = ConcurrentHashMap.newKeySet();

	private volatile boolean ready;

	private volatile boolean closing;

	DatasetCopyWorkerLauncher(TargetStorageResolver targets, DatasetCopyWorkerProjections projections,
			Duration timeout) {
		if (timeout.toMillis() < 1 || timeout.compareTo(Duration.ofDays(1)) > 0)
			throw new IllegalArgumentException("Dataset Copy worker timeout must be positive and at most one day");
		this.targets = targets;
		this.projections = projections;
		this.timeout = timeout;
	}

	boolean ready() {
		return this.ready && !this.closing;
	}

	@EventListener(ApplicationReadyEvent.class)
	@Scheduled(fixedDelayString = "${skywright.dataset-catalog.maintenance-delay:PT5S}")
	synchronized void recover() {
		if (this.ready || this.closing)
			return;
		try {
			boolean pending = false;
			for (var projection : this.projections.open()) {
				var process = projection.pid() == null || projection.startedAt() == null
						? java.util.Optional.<ProcessHandle>empty()
						: ProcessHandle.of(projection.pid())
							.filter(ProcessHandle::isAlive)
							.filter(handle -> handle.info()
								.startInstant()
								.filter(projection.startedAt()::equals)
								.isPresent());
				if (process.isPresent()) {
					process.get().destroyForcibly();
					pending = true;
					continue;
				}
				this.projections.released(projection.id());
				deleteFiles(projection.directory() == null ? null : Path.of(projection.directory()));
			}
			this.ready = !pending;
		}
		catch (RuntimeException unavailable) {
			// Keep admission closed until custody recovery can be recorded durably.
		}
	}

	VerifiedDatasetReplacement execute(String action, DatasetDefinitionView definition,
			List<DatasetManifestEntry> manifest, DatasetCopyView copy, UUID operationId, long generation) {
		if (!this.ready())
			throw unavailable();
		var target = this.targets.resolveDataset(copy.targetStorageId(), "transfer-worker");
		var credentials = target.credentials().resolveCredentials();
		UUID attempt = this.projections.projected(copy.id(), target.credentialBindingId(),
				target.credentialBindingRevision());
		Path directory = null;
		Process process = null;
		try {
			directory = Files.createTempDirectory("skywright-dataset-copy-" + attempt + "-",
					PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
			this.projections.prepared(attempt, directory);
			var parent = ProcessHandle.current();
			var job = new DatasetCopyWorkerJob(attempt, action, target.endpoint(), target.bucket(),
					target.region().id(), target.pathStyleAccess(), target.compatibilityOptions(), definition, manifest,
					copy, operationId, generation, this.timeout.toMillis(), parent.pid(),
					parent.info().startInstant().orElseThrow());
			Path jobPath = directory.resolve("job.json");
			Path result = directory.resolve("result.json");
			JSON.writeValue(jobPath.toFile(), job);
			if (Files.size(jobPath) > 32 * 1024 * 1024)
				throw unavailable();
			var command = new java.util.ArrayList<>(WorkerProcessCommand.command(DatasetCopyWorkerMain.class,
					List.of(jobPath.toString(), result.toString())));
			command.add(1, "-Xmx256m");
			var builder = new ProcessBuilder(command).redirectErrorStream(true)
				.redirectOutput(ProcessBuilder.Redirect.DISCARD);
			builder.environment().clear();
			if (!this.ready())
				throw unavailable();
			process = builder.start();
			this.active.add(process);
			this.projections.launched(attempt, process.pid(), process.info().startInstant().orElseThrow());
			var credential = new DatasetCopyWorkerCredential(credentials.accessKeyId(), credentials.secretAccessKey(),
					credentials instanceof AwsSessionCredentials session ? session.sessionToken() : null);
			byte[] encoded = JSON.writeValueAsBytes(credential);
			try (var input = process.getOutputStream()) {
				if (encoded.length > 16_384)
					throw unavailable();
				input.write(encoded);
			}
			finally {
				java.util.Arrays.fill(encoded, (byte) 0);
			}
			if (!process.waitFor(this.timeout.toMillis(), TimeUnit.MILLISECONDS))
				throw new DatasetCatalogConflictException("DATASET_COPY_WORKER_DEADLINE",
						"Dataset Copy verification deadline exceeded");
			if (process.exitValue() != 0 || !Files.isRegularFile(result) || Files.size(result) > 16_384)
				throw unavailable();
			var receipt = JSON.readValue(result.toFile(), DatasetCopyWorkerReceipt.class);
			if (!receipt.attemptId().equals(attempt) || receipt.workerPid() != process.pid())
				throw unavailable();
			if (receipt.failureCode() != null)
				throw new DatasetCatalogConflictException(receipt.failureCode(), "Dataset storage verification failed");
			return receipt.replacement();
		}
		catch (IOException failure) {
			throw unavailable();
		}
		catch (InterruptedException failure) {
			Thread.currentThread().interrupt();
			throw unavailable();
		}
		finally {
			Path owned = directory;
			if (process == null) {
				this.projections.released(attempt);
				deleteFiles(owned);
			}
			else {
				Process worker = process;
				worker.destroyForcibly();
				Runnable release = () -> {
					try {
						this.projections.released(attempt);
						deleteFiles(owned);
					}
					catch (RuntimeException unavailable) {
						this.ready = false;
					}
					finally {
						this.active.remove(worker);
					}
				};
				if (worker.isAlive())
					worker.onExit().thenRun(release);
				else
					release.run();
			}
		}
	}

	@PreDestroy
	void close() {
		this.closing = true;
		this.active.forEach(Process::destroyForcibly);
	}

	private static DatasetCatalogConflictException unavailable() {
		return new DatasetCatalogConflictException("DATASET_COPY_WORKER_UNAVAILABLE",
				"Dataset Copy Transfer Worker is unavailable");
	}

	static void deleteFiles(Path directory) {
		if (directory == null)
			return;
		try {
			Files.deleteIfExists(directory.resolve("job.json"));
			Files.deleteIfExists(directory.resolve("result.json"));
			Files.deleteIfExists(directory.resolve("result.json.pending"));
			Files.deleteIfExists(directory);
		}
		catch (IOException ignored) {
			/* Non-secret job metadata can be cleaned up after storage recovery. */ }
	}

}
