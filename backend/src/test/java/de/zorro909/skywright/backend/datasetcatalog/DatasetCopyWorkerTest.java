package de.zorro909.skywright.backend.datasetcatalog;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DatasetCopyWorkerTest {

	@Test
	void tricklingResponseReachesHardWorkerDeadlineInSeparateProcess() throws Exception {
		var received = new CountDownLatch(1);
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			exchange.sendResponseHeaders(200, 100_000);
			received.countDown();
			try (var body = exchange.getResponseBody()) {
				for (int index = 0; index < 100_000; index++) {
					body.write(0);
					body.flush();
					try {
						Thread.sleep(20);
					}
					catch (InterruptedException cancelled) {
						Thread.currentThread().interrupt();
						break;
					}
				}
			}
			catch (java.io.IOException closed) {
				/* The deadline must close this body. */ }
		});
		server.start();
		UUID storageId = UUID.randomUUID();
		UUID attempt = UUID.randomUUID();
		var now = Instant.now();
		var generation = new DatasetCopyGenerationView(1, "copy", "manifest", "fingerprint", 100_000, now, now, true,
				DatasetCopyAvailability.AVAILABLE);
		var copy = new DatasetCopyView(UUID.randomUUID(), storageId, DatasetCopyRole.REPLICA, 1, generation,
				List.of(generation), 0);
		var directory = java.nio.file.Files.createTempDirectory("dataset-copy-deadline-test-");
		var json = tools.jackson.databind.json.JsonMapper.builder().build();
		var parent = ProcessHandle.current();
		var job = new DatasetCopyWorkerJob(attempt, "verify",
				URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "bucket", "us-east-1", true, Map.of(),
				null,
				List.of(new DatasetManifestEntry("shard", 100_000, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")),
				copy, null, 0, 8_000, parent.pid(), parent.info().startInstant().orElseThrow());
		var jobPath = directory.resolve("job.json");
		var result = directory.resolve("result.json");
		json.writeValue(jobPath.toFile(), job);
		var builder = new ProcessBuilder(de.zorro909.skywright.backend.worker.WorkerProcessCommand
			.command(DatasetCopyWorkerMain.class, List.of(jobPath.toString(), result.toString())))
			.redirectErrorStream(true)
			.redirectOutput(ProcessBuilder.Redirect.DISCARD);
		builder.environment().clear();
		Process process = builder.start();
		try {
			try (var input = process.getOutputStream()) {
				json.writeValue(input, new DatasetCopyWorkerCredential("test-key", "test-secret", null));
			}
			assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
			assertThat(received.getCount()).as("The actual child must acquire and consume the slow response").isZero();
			assertThat(process.pid()).isNotEqualTo(ProcessHandle.current().pid());
			assertThat(process.exitValue()).isEqualTo(74);
			assertThat(java.nio.file.Files.exists(result)).isFalse();
		}
		finally {
			process.destroyForcibly();
			server.stop(0);
			DatasetCopyWorkerLauncher.deleteFiles(directory);
		}
	}

}
