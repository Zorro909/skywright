package de.zorro909.skywright.backend.datasetcatalog;

import de.zorro909.skywright.backend.runstore.ResolvedTargetStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import tools.jackson.databind.json.JsonMapper;

/** Standalone Transfer Worker; no Spring context or catalogue mutation. */
public final class DatasetCopyWorkerMain {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private DatasetCopyWorkerMain() {
	}

	public static void main(String[] arguments) throws Exception {
		if (arguments.length != 2)
			throw new IllegalArgumentException("Expected job and receipt paths");
		Path jobPath = Path.of(arguments[0]);
		if (Files.size(jobPath) > 32 * 1024 * 1024)
			throw new IllegalArgumentException("Job metadata exceeds limit");
		var job = JSON.readValue(jobPath.toFile(), DatasetCopyWorkerJob.class);
		if (job.timeoutMillis() < 1 || job.timeoutMillis() > TimeUnit.DAYS.toMillis(1))
			throw new IllegalArgumentException("Invalid worker deadline");
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(job.timeoutMillis());
		Thread.ofPlatform().daemon().name("dataset-copy-watchdog").start(() -> {
			while (true) {
				boolean parentAlive = ProcessHandle.of(job.parentPid())
					.filter(ProcessHandle::isAlive)
					.filter(parent -> parent.info().startInstant().filter(job.parentStartedAt()::equals).isPresent())
					.isPresent();
				if (!parentAlive || System.nanoTime() >= deadline)
					Runtime.getRuntime().halt(74);
				try {
					Thread.sleep(100);
				}
				catch (InterruptedException interrupted) {
					Runtime.getRuntime().halt(74);
				}
			}
		});
		byte[] encoded = System.in.readNBytes(16_385);
		if (encoded.length > 16_384)
			throw new IllegalArgumentException("Credential input exceeds limit");
		var credential = JSON.readValue(encoded, DatasetCopyWorkerCredential.class);
		java.util.Arrays.fill(encoded, (byte) 0);
		var aws = credential.sessionToken() == null
				? AwsBasicCredentials.create(credential.accessKeyId(), credential.secretAccessKey())
				: AwsSessionCredentials.create(credential.accessKeyId(), credential.secretAccessKey(),
						credential.sessionToken());
		var target = new ResolvedTargetStorage(job.copy().targetStorageId().toString(), job.endpoint(), job.bucket(),
				Region.of(job.region()), job.pathStyleAccess(), job.compatibilityOptions(),
				StaticCredentialsProvider.create(aws), "dataset-catalog", "maintenance", null, 0);
		var storage = new DatasetCopyWorkerStorage(target);
		DatasetCopyWorkerReceipt receipt;
		try {
			VerifiedDatasetReplacement replacement = switch (job.action()) {
				case "verify" -> {
					storage.verify(job.definition(), job.manifest(), job.copy());
					yield null;
				}
				case "stage" ->
					storage.stageReplacement(job.definition(), job.manifest(), job.copy(), job.operationId());
				case "replacement" ->
					storage.verifyReplacement(job.definition(), job.manifest(), job.copy(), job.operationId());
				case "delete" -> {
					storage.deleteAndVerify(job.manifest(), job.copy(), job.generation());
					yield null;
				}
				default -> throw new IllegalArgumentException("Unknown copy operation");
			};
			receipt = new DatasetCopyWorkerReceipt(job.attemptId(), ProcessHandle.current().pid(), null, replacement);
		}
		catch (DatasetCatalogException failure) {
			receipt = new DatasetCopyWorkerReceipt(job.attemptId(), ProcessHandle.current().pid(), failure.errorCode(),
					null);
		}
		catch (RuntimeException failure) {
			receipt = new DatasetCopyWorkerReceipt(job.attemptId(), ProcessHandle.current().pid(),
					"DATASET_STORAGE_UNAVAILABLE", null);
		}
		Path result = Path.of(arguments[1]);
		Path pending = result.resolveSibling("result.json.pending");
		JSON.writeValue(pending.toFile(), receipt);
		Files.move(pending, result, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
	}

}
