package de.zorro909.skywright.backend.runlog;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Leases coordinate capture; a locked publication rejects replaced producers. */
@Service
@Transactional
public class RunLogCaptureStore {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final EntityManager entities;

	private final Clock clock;

	RunLogCaptureStore(EntityManager entities, Clock clock) {
		this.entities = entities;
		this.clock = clock;
	}

	public record Claim(UUID runId, UUID token) {
	}

	public record Finalization(UUID runId, String manifestKey, String sha256, Instant publishedAt) {
	}

	@Transactional(readOnly = true)
	Saved snapshot(UUID runId) {
		var row = entities.find(RunLogCaptureEntity.class, runId);
		return row == null ? new Saved(RunLogCheckpoint.initial(), null, null) : new Saved(
				JSON.readValue(row.checkpoint, RunLogCheckpoint.class), row.manifestSha256, row.finalizedAt);
	}

	record Saved(RunLogCheckpoint checkpoint, String manifestDigest, Instant finalizedAt) {
	}

	public Claim claim(UUID runId) {
		Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
		// The parent row exists before capture; lock it to serialize first-row creation.
		entities.createQuery("select r from RunRecordEntity r where r.id = :run", Object.class)
			.setParameter("run", runId)
			.setLockMode(LockModeType.PESSIMISTIC_WRITE)
			.setHint("jakarta.persistence.lock.timeout", 0)
			.getSingleResult();
		var row = entities.find(RunLogCaptureEntity.class, runId, LockModeType.PESSIMISTIC_WRITE,
				java.util.Map.of("jakarta.persistence.lock.timeout", 0));
		if (row == null) {
			row = new RunLogCaptureEntity();
			row.runId = runId;
			row.checkpoint = JSON.writeValueAsString(RunLogCheckpoint.initial());
			row.nextAttemptAt = now;
			entities.persist(row);
		}
		if (row.manifestSha256 != null || row.nextAttemptAt.isAfter(now)
				|| row.leaseUntil != null && row.leaseUntil.isAfter(now))
			return null;
		row.leaseToken = UUID.randomUUID();
		row.leaseUntil = now.plusSeconds(60);
		return new Claim(runId, row.leaseToken);
	}

	void publish(Claim claim, Function<RunLogCheckpoint, Saved> publication) {
		var row = entities.find(RunLogCaptureEntity.class, claim.runId(), LockModeType.PESSIMISTIC_WRITE,
				java.util.Map.of("jakarta.persistence.lock.timeout", 0));
		if (row == null || !claim.token().equals(row.leaseToken) || row.manifestSha256 != null)
			return;
		var saved = publication.apply(JSON.readValue(row.checkpoint, RunLogCheckpoint.class));
		String checkpoint = JSON.writeValueAsString(saved.checkpoint());
		if (checkpoint.length() > 32768)
			throw new IllegalStateException("Archive checkpoint exceeds its bound");
		row.checkpoint = checkpoint;
		row.manifestSha256 = saved.manifestDigest();
		row.finalizedAt = saved.finalizedAt();
		row.nextAttemptAt = saved.manifestDigest() == null ? clock.instant().plusSeconds(5) : null;
		row.leaseToken = null;
		row.leaseUntil = null;
	}

	/**
	 * Manifest publication closes this producer before relocation/deletion can proceed.
	 */
	@Transactional(readOnly = true)
	public Finalization finalization(UUID runId) {
		var row = entities.find(RunLogCaptureEntity.class, runId);
		return row == null || row.manifestSha256 == null ? null
				: new Finalization(runId, "skypilot/logs/manifest.json", row.manifestSha256, row.finalizedAt);
	}

}
