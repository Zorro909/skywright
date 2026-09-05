package de.zorro909.skywright.backend.credential;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Append-only usage evidence. A release requires the caller to finish every consumer. */
@Service
public class LocalProjectionFacts {

	public record Fact(UUID consumerId, String slot, UUID bindingId, long revision, String role, Instant projectedAt,
			Instant releasedAt) {
	}

	private final EntityManager entities;

	private final Clock clock;

	LocalProjectionFacts(EntityManager entities, Clock clock) {
		this.entities = entities;
		this.clock = clock;
	}

	@Transactional
	public void begin(UUID consumerId, String slot, CredentialBinding binding) {
		if (consumerId == null || !List.of("dataset", "run-store", "runtime-pull", "backend", "skypilot").contains(slot)
				|| this.entities.find(LocalProjectionRelease.class, consumerId) != null) {
			throw new IllegalArgumentException("Invalid Credential Projection consumer");
		}
		this.entities.persist(new LocalProjectionRecord(consumerId, slot, binding, this.clock.instant()));
		// The unique consumer/slot key forbids silently replacing a Run's revision.
		this.entities.flush();
	}

	@Transactional
	public void release(UUID consumerId) {
		if (this.entities.find(LocalProjectionRelease.class, consumerId) == null) {
			this.entities.persist(new LocalProjectionRelease(consumerId, this.clock.instant()));
		}
	}

	@Transactional(readOnly = true)
	public List<Fact> forConsumer(UUID consumerId) {
		var release = this.entities.find(LocalProjectionRelease.class, consumerId);
		return this.entities
			.createQuery("select p from LocalProjectionRecord p where p.consumerId = :consumer",
					LocalProjectionRecord.class)
			.setParameter("consumer", consumerId)
			.getResultStream()
			.map(p -> new Fact(p.consumerId, p.slot, p.bindingId, p.bindingRevision, p.consumerRole, p.projectedAt,
					release == null ? null : release.releasedAt))
			.toList();
	}

}
