package de.zorro909.skywright.backend.datasetpublication;

import de.zorro909.skywright.backend.runstore.ResolvedTargetStorage;
import de.zorro909.skywright.backend.targetstorage.TargetStorageResolver;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import tools.jackson.databind.json.JsonMapper;

final class DatasetPublicationWorkerLauncher {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final TargetStorageResolver targetStorages;

	private final DatasetPublicationCredentialProjectionLifecycle projections;

	private final int verificationConcurrency;

	private final Set<ActiveWorker> activeWorkers = ConcurrentHashMap.newKeySet();

	DatasetPublicationWorkerLauncher(TargetStorageResolver targetStorages,
			DatasetPublicationCredentialProjectionLifecycle projections, int verificationConcurrency) {
		this.targetStorages = targetStorages;
		this.projections = projections;
		this.verificationConcurrency = verificationConcurrency;
	}

	DatasetPublicationWorkerResult verify(DatasetPublicationView publication) {
		ResolvedTargetStorage target = this.targetStorages.resolveDataset(publication.targetStorageId(),
				"transfer-worker");
		AwsCredentials credentials = target.credentials().resolveCredentials();
		Path directory = null;
		Process worker = null;
		UUID projectionId = null;
		ActiveWorker activeWorker = null;
		try {
			projectionId = this.projections.projected(publication.publicationId(), target.credentialBindingId(),
					target.credentialBindingRevision());
			directory = Files.createTempDirectory("skywright-dataset-worker-job-" + projectionId + "-");
			this.projections.prepared(projectionId, directory);
			Path job = directory.resolve("job.json");
			Path result = directory.resolve("result.json");
			JSON.writeValue(job.toFile(), new DatasetPublicationWorkerJob(target.endpoint(), target.bucket(),
					target.region().id(), target.pathStyleAccess(),
					"enabled".equals(target.compatibilityOptions().get("chunkedEncoding")),
					publication.formatIdentity(), publication.manifestIdentity(), publication.contentFingerprint(),
					publication.objectCount(), publication.byteCount(), publication.payloadLocation(),
					publication.operationLocation(), this.verificationConcurrency));
			var process = new ProcessBuilder(command(job, result)).redirectErrorStream(true)
				.redirectOutput(ProcessBuilder.Redirect.DISCARD);
			clearEnvironment(process.environment());
			worker = process.start();
			activeWorker = new ActiveWorker(worker, projectionId, directory);
			this.activeWorkers.add(activeWorker);
			ActiveWorker trackedWorker = activeWorker;
			worker.onExit().thenRun(() -> completeIfReady(trackedWorker));
			Instant workerStartedAt = requireWorkerStartedAt(worker.toHandle().info().startInstant());
			this.projections.launched(projectionId, worker.pid(), workerStartedAt);
			try (var credentialStream = worker.getOutputStream()) {
				JSON.writeValue(credentialStream, credential(credentials));
			}
			awaitCompletion(worker);
			if (!Files.isRegularFile(result)) {
				return failure();
			}
			return JSON.readValue(result.toFile(), DatasetPublicationWorkerResult.class);
		}
		catch (IOException exception) {
			return failure();
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			return failure();
		}
		finally {
			if (activeWorker != null) {
				terminate(activeWorker.process);
				activeWorker.verificationFinished.set(true);
				completeIfReady(activeWorker);
			}
			else {
				if (projectionId != null) {
					this.projections.released(projectionId);
				}
				deleteJobFiles(directory);
			}
		}
	}

	static void awaitCompletion(Process worker) throws InterruptedException {
		worker.waitFor();
	}

	static Instant requireWorkerStartedAt(java.util.Optional<Instant> workerStartedAt) throws IOException {
		return workerStartedAt.orElseThrow(() -> new IOException("Worker start identity is unavailable"));
	}

	static void clearEnvironment(Map<String, String> environment) {
		environment.clear();
	}

	static DatasetPublicationWorkerCredential credential(AwsCredentials credentials) {
		String sessionToken = null;
		if (credentials instanceof AwsSessionCredentials session) {
			sessionToken = session.sessionToken();
		}
		return new DatasetPublicationWorkerCredential(credentials.accessKeyId(), credentials.secretAccessKey(),
				sessionToken);
	}

	private static boolean terminate(Process worker) {
		if (!worker.isAlive()) {
			return true;
		}
		worker.destroy();
		try {
			if (!worker.waitFor(5, TimeUnit.SECONDS)) {
				worker.destroyForcibly();
				return worker.waitFor(5, TimeUnit.SECONDS);
			}
			return true;
		}
		catch (InterruptedException exception) {
			worker.destroyForcibly();
			Thread.currentThread().interrupt();
			return !worker.isAlive();
		}
	}

	@PreDestroy
	void close() {
		this.activeWorkers.forEach(activeWorker -> {
			terminate(activeWorker.process);
			activeWorker.verificationFinished.set(true);
			completeIfReady(activeWorker);
		});
	}

	private void completeIfReady(ActiveWorker activeWorker) {
		if (!activeWorker.verificationFinished.get() || activeWorker.process.isAlive()
				|| !activeWorker.completing.compareAndSet(false, true)) {
			return;
		}
		try {
			this.projections.released(activeWorker.projectionId);
			deleteJobFiles(activeWorker.directory);
			this.activeWorkers.remove(activeWorker);
		}
		catch (RuntimeException exception) {
			activeWorker.completing.set(false);
			throw exception;
		}
	}

	static void deleteJobFiles(Path directory) {
		if (directory == null) {
			return;
		}
		try {
			Files.deleteIfExists(directory.resolve("job.json"));
			Files.deleteIfExists(directory.resolve("result.json"));
			Files.deleteIfExists(directory);
		}
		catch (IOException ignored) {
			// Job files contain no credential and are not durable publication state.
		}
	}

	private static java.util.List<String> command(Path job, Path result) {
		String executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
		var command = new ArrayList<String>();
		command.add(executable);
		if (classPath.endsWith(".jar") && !classPath.contains(System.getProperty("path.separator"))) {
			command.add("-Dloader.main=" + DatasetPublicationWorkerMain.class.getName());
			command.add("-cp");
			command.add(classPath);
			command.add("org.springframework.boot.loader.launch.PropertiesLauncher");
		}
		else {
			command.add("-cp");
			command.add(classPath);
			command.add(DatasetPublicationWorkerMain.class.getName());
		}
		command.add(job.toString());
		command.add(result.toString());
		return command;
	}

	private static DatasetPublicationWorkerResult failure() {
		return new DatasetPublicationWorkerResult(false, java.util.List.of(), 0, 0, null, 0,
				"DATASET_VERIFICATION_UNAVAILABLE", true);
	}

	private static final class ActiveWorker {

		private final Process process;

		private final UUID projectionId;

		private final Path directory;

		private final AtomicBoolean verificationFinished = new AtomicBoolean();

		private final AtomicBoolean completing = new AtomicBoolean();

		private ActiveWorker(Process process, UUID projectionId, Path directory) {
			this.process = process;
			this.projectionId = projectionId;
			this.directory = directory;
		}

	}

}
