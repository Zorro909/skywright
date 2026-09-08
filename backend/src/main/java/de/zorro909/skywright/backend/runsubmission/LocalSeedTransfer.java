package de.zorro909.skywright.backend.runsubmission;

import de.zorro909.skywright.backend.rundefinition.RunDefinition;
import de.zorro909.skywright.backend.runtimeassembly.RuntimeMaterials;
import de.zorro909.skywright.backend.targetstorage.TargetStorageResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
class LocalSeedTransfer {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final LocalSeedPreparations preparations;

	private final RunAcceptanceStore runs;

	private final TargetStorageResolver storages;

	private final Semaphore workers = new Semaphore(1);

	LocalSeedTransfer(LocalSeedPreparations preparations, RunAcceptanceStore runs, TargetStorageResolver storages) {
		this.preparations = preparations;
		this.runs = runs;
		this.storages = storages;
	}

	RuntimeMaterials.SourceCheckpoint prepare(UUID runId, LocalRunRequest request, RunDefinition definition) {
		var seed = request.checkpointSeed();
		if (seed == null)
			return null;
		if (!workers.tryAcquire())
			throw new RunSubmissionException("SEED_WORKER_BUSY", 503);
		try {
			var claim = preparations.claim(request.submissionId(), runId, definition);
			if (claim.verifiedAt() == null)
				copy(claim.token(), runId, request, definition);
			return new RuntimeMaterials.SourceCheckpoint(seed.predecessorRunId(), seed.checkpointReference(),
					JSON.treeToValue(definition.value().at("/storage/execution"), RuntimeMaterials.SourceStorage.class),
					runId);
		}
		finally {
			workers.release();
		}
	}

	private void copy(UUID token, UUID runId, LocalRunRequest request, RunDefinition definition) {
		var seed = request.checkpointSeed();
		String project = request.trainingProjectId().toString();
		var source = storages.resolveRunOutputLocation(runs.currentStorage(seed.predecessorRunId()), "transfer-worker",
				project, seed.predecessorRunId().toString());
		var destination = storages.resolveRunOutputLocation(definition.value().at("/storage/execution"),
				"transfer-worker", project, runId.toString());
		var job = new LocalSeedWorkerJob(LocalSeedWorkerJob.Storage.from(source),
				LocalSeedWorkerJob.Storage.from(destination), request.trainingProjectId(), seed.predecessorRunId(),
				runId, seed.checkpointReference(), request.manifestArtifactDigest());
		Path directory = null;
		Process process = null;
		boolean projected = false;
		try {
			directory = Files.createTempDirectory("skywright-seed-" + token + "-");
			Files.setPosixFilePermissions(directory,
					java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
			preparations.workerEvent(token, "projected", Map.of("submissionId", request.submissionId(), "job", job,
					"directory", directory.toString(), "role", "transfer-worker"));
			projected = true;
			var credentials = new LocalSeedWorkerMain.Credentials(credential(source.credentials().resolveCredentials()),
					credential(destination.credentials().resolveCredentials()));
			byte[] secret = JSON.writeValueAsBytes(credentials);
			if (secret.length > 16384)
				throw new IllegalArgumentException("Seed projection exceeds its byte budget");
			Path input = directory.resolve("job.json");
			Path receipt = directory.resolve("receipt.json");
			JSON.writeValue(input.toFile(), job);
			var command = de.zorro909.skywright.backend.worker.WorkerProcessCommand.command(LocalSeedWorkerMain.class,
					java.util.List.of(input.toString(), receipt.toString()));
			command.add(1, "-Xmx128m");
			var builder = new ProcessBuilder(command).redirectErrorStream(true)
				.redirectOutput(ProcessBuilder.Redirect.DISCARD);
			builder.environment().clear();
			process = builder.start();
			preparations.workerEvent(token, "launched",
					Map.of("pid", process.pid(), "startedAt", process.info().startInstant().orElseThrow().toString()));
			try (var stream = process.getOutputStream()) {
				stream.write(secret);
			}
			finally {
				java.util.Arrays.fill(secret, (byte) 0);
			}
			if (!process.waitFor(250, TimeUnit.SECONDS) || process.exitValue() != 0 || !Files.isRegularFile(receipt)
					|| Files.size(receipt) > 16384)
				throw new RunSubmissionException("SEED_TRANSFER_UNAVAILABLE", 503);
			var result = JSON.readValue(receipt.toFile(), LocalSeedWorkerMain.Receipt.class);
			var reference = de.zorro909.skywright.backend.runstore.CheckpointReference
				.parse(seed.checkpointReference());
			if (!result.key().equals(job.destinationKey()) || !result.digest().equals(reference.digest())
					|| result.size() < 1 || result.size() > LocalSeedWorkerMain.MAX_BYTES
					|| !result.predecessorRunId().equals(seed.predecessorRunId().toString())
					|| !result.projectVersion().equals(request.manifestArtifactDigest()))
				throw new RunSubmissionException("SEED_RECEIPT_INVALID", 503);
			preparations.verified(request.submissionId(), token, JSON.writeValueAsString(result));
		}
		catch (InterruptedException failure) {
			Thread.currentThread().interrupt();
			throw new RunSubmissionException("SEED_TRANSFER_UNAVAILABLE", 503);
		}
		catch (java.io.IOException failure) {
			throw new RunSubmissionException("SEED_TRANSFER_UNAVAILABLE", 503);
		}
		finally {
			if (process != null && process.isAlive()) {
				process.destroyForcibly();
				try {
					process.waitFor(5, TimeUnit.SECONDS);
				}
				catch (InterruptedException failure) {
					Thread.currentThread().interrupt();
				}
			}
			if (process == null || !process.isAlive()) {
				if (projected)
					preparations.workerEvent(token, "released", Map.of());
				if (directory != null) {
					try (var paths = Files.list(directory)) {
						for (var path : paths.toList())
							Files.deleteIfExists(path);
						Files.deleteIfExists(directory);
					}
					catch (java.io.IOException ignored) {
						/*
						 * Nonsecret files remain owned by the recorded worker directory.
						 */ }
				}
			}
		}
	}

	private static LocalSeedWorkerMain.Credential credential(
			software.amazon.awssdk.auth.credentials.AwsCredentials value) {
		return new LocalSeedWorkerMain.Credential(value.accessKeyId(), value.secretAccessKey(),
				value instanceof software.amazon.awssdk.auth.credentials.AwsSessionCredentials session
						? session.sessionToken() : null);
	}

}
