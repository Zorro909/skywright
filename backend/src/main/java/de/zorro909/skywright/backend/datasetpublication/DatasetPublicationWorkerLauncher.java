package de.zorro909.skywright.backend.datasetpublication;

import de.zorro909.skywright.backend.runstore.ResolvedTargetStorage;
import de.zorro909.skywright.backend.targetstorage.TargetStorageResolver;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import tools.jackson.databind.json.JsonMapper;

final class DatasetPublicationWorkerLauncher {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final TargetStorageResolver targetStorages;

	private final DatasetPublicationCredentialProjections projections;

	private final Set<Process> activeWorkers = ConcurrentHashMap.newKeySet();

	DatasetPublicationWorkerLauncher(TargetStorageResolver targetStorages,
			DatasetPublicationCredentialProjections projections) {
		this.targetStorages = targetStorages;
		this.projections = projections;
	}

	DatasetPublicationWorkerResult verify(DatasetPublicationView publication) {
		ResolvedTargetStorage target = this.targetStorages.resolveDataset(publication.targetStorageId(),
				"transfer-worker");
		AwsCredentials credentials = target.credentials().resolveCredentials();
		Path directory = null;
		Process worker = null;
		UUID projectionId = null;
		try {
			projectionId = this.projections.projected(publication.publicationId(), target.credentialBindingId(),
					target.credentialBindingRevision());
			directory = Files.createTempDirectory("skywright-dataset-worker-job-");
			Path job = directory.resolve("job.json");
			Path result = directory.resolve("result.json");
			JSON.writeValue(job.toFile(),
					new DatasetPublicationWorkerJob(target.endpoint(), target.bucket(), target.region().id(),
							target.pathStyleAccess(),
							"enabled".equals(target.compatibilityOptions().get("chunkedEncoding")),
							publication.formatIdentity(), publication.manifestIdentity(),
							publication.contentFingerprint(), publication.objectCount(), publication.byteCount(),
							publication.payloadLocation(), publication.operationLocation()));
			var process = new ProcessBuilder(command(job, result)).redirectErrorStream(true)
				.redirectOutput(ProcessBuilder.Redirect.DISCARD);
			projectEnvironment(process.environment(), credentials);
			worker = process.start();
			this.activeWorkers.add(worker);
			if (!worker.waitFor(Duration.ofMinutes(10).toMillis(), TimeUnit.MILLISECONDS)) {
				terminate(worker);
				return failure();
			}
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
			if (worker != null) {
				terminate(worker);
				this.activeWorkers.remove(worker);
			}
			if (projectionId != null) {
				this.projections.released(projectionId);
			}
			if (directory != null) {
				try {
					Files.deleteIfExists(directory.resolve("job.json"));
					Files.deleteIfExists(directory.resolve("result.json"));
					Files.deleteIfExists(directory);
				}
				catch (IOException ignored) {
					// Job files contain no credential and are not durable publication
					// state.
				}
			}
		}
	}

	static void projectEnvironment(Map<String, String> environment, AwsCredentials credentials) {
		environment.clear();
		environment.put("AWS_ACCESS_KEY_ID", credentials.accessKeyId());
		environment.put("AWS_SECRET_ACCESS_KEY", credentials.secretAccessKey());
		if (credentials instanceof AwsSessionCredentials session) {
			environment.put("AWS_SESSION_TOKEN", session.sessionToken());
		}
	}

	private static void terminate(Process worker) {
		if (!worker.isAlive()) {
			return;
		}
		worker.destroy();
		try {
			if (!worker.waitFor(5, TimeUnit.SECONDS)) {
				worker.destroyForcibly();
				worker.waitFor(5, TimeUnit.SECONDS);
			}
		}
		catch (InterruptedException exception) {
			worker.destroyForcibly();
			Thread.currentThread().interrupt();
		}
	}

	@PreDestroy
	void close() {
		this.activeWorkers.forEach(DatasetPublicationWorkerLauncher::terminate);
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

}
