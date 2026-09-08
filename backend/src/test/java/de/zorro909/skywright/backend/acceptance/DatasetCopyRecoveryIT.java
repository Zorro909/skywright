package de.zorro909.skywright.backend.acceptance;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("real-service")
class DatasetCopyRecoveryIT {

	@Test
	void restartTerminatesRecordedWorkerBeforeReleasingCustodyAndIgnoresReusedPid() throws Exception {
		Process abandoned = new ProcessBuilder("sleep", "120").start();
		Process unrelated = new ProcessBuilder("sleep", "120").start();
		var directory = Files.createTempDirectory("dataset-copy-recovery-");
		Files.writeString(directory.resolve("job.json"), "{}");
		try (var backend = BackendFixture.startWithTargetStorageIntegration()) {
			var jdbc = new JdbcTemplate(backend.bean(javax.sql.DataSource.class));
			insertProjection(jdbc, abandoned, abandoned.info().startInstant().orElseThrow(), directory.toString());
			insertProjection(jdbc, unrelated, unrelated.info().startInstant().orElseThrow().plusSeconds(1), null);
			backend.restart();
			jdbc = new JdbcTemplate(backend.bean(javax.sql.DataSource.class));
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
			while (System.nanoTime() < deadline && jdbc.queryForObject(
					"select count(*) from skywright.dataset_copy_worker_projection where released_at is null",
					Long.class) != 0) {
				Thread.sleep(100);
			}
			assertThat(jdbc.queryForObject(
					"select count(*) from skywright.dataset_copy_worker_projection where released_at is null",
					Long.class))
				.isZero();
			assertThat(abandoned.isAlive()).isFalse();
			assertThat(unrelated.isAlive()).as("A matching PID without matching start time is not our worker").isTrue();
			assertThat(Files.exists(directory)).isFalse();
		}
		finally {
			abandoned.destroyForcibly();
			unrelated.destroyForcibly();
			Files.deleteIfExists(directory.resolve("job.json"));
			Files.deleteIfExists(directory);
		}
	}

	private static void insertProjection(JdbcTemplate jdbc, Process process, Instant startedAt, String directory) {
		jdbc.update(
				"insert into skywright.dataset_copy_worker_projection (projection_id, copy_id, binding_id, binding_revision, consumer_role, projected_at, worker_pid, worker_started_at, job_directory) values (?, ?, ?, 1, 'transfer-worker', current_timestamp, ?, ?, ?)",
				UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), process.pid(),
				java.sql.Timestamp.from(startedAt), directory);
	}

}
