package de.zorro909.skywright.backend.runsubmission;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import de.zorro909.skywright.backend.rundefinition.RunDefinition;

@Repository
class LocalSeedPreparations {

	private final EntityManager entities;

	private final TransactionTemplate transactions;

	private final Clock clock;

	LocalSeedPreparations(EntityManager entities, PlatformTransactionManager manager, Clock clock) {
		this.entities = entities;
		this.clock = clock;
		transactions = new TransactionTemplate(manager);
		transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	UUID reserve(LocalRunRequest request, String digest) {
		try {
			return transactions.execute(ignored -> {
				var row = entities.find(LocalSeedPreparationEntity.class, request.submissionId());
				if (row == null) {
					row = new LocalSeedPreparationEntity(request, digest);
					entities.persist(row);
					entities.flush();
				}
				if (!row.requestDigest.equals(digest))
					throw new RunSubmissionException("RUN_SUBMISSION_IDENTITY_CONFLICT", 409);
				return row.runId;
			});
		}
		catch (RuntimeException failure) {
			return transactions.execute(ignored -> {
				var row = entities.find(LocalSeedPreparationEntity.class, request.submissionId());
				if (row == null)
					throw failure;
				if (!row.requestDigest.equals(digest))
					throw new RunSubmissionException("RUN_SUBMISSION_IDENTITY_CONFLICT", 409);
				return row.runId;
			});
		}
	}

	record Claim(UUID token, Instant verifiedAt) {
	}

	Claim claim(UUID submissionId, UUID runId, RunDefinition definition) {
		return transactions.execute(ignored -> {
			var row = entities.find(LocalSeedPreparationEntity.class, submissionId, LockModeType.PESSIMISTIC_WRITE);
			if (row == null || !row.runId.equals(runId))
				throw new RunSubmissionException("SEED_PREPARATION_UNAVAILABLE", 503);
			String document = definition.encode();
			if (row.definition != null && !row.definition.equals(document))
				throw new RunSubmissionException("SEED_PREPARATION_DEFINITION_CONFLICT", 409);
			row.definition = document;
			if (row.verifiedAt != null)
				return new Claim(null, row.verifiedAt);
			if (row.leaseUntil != null && row.leaseUntil.isAfter(clock.instant()))
				throw new RunSubmissionException("SEED_PREPARATION_IN_PROGRESS", 503);
			row.leaseToken = UUID.randomUUID();
			row.leaseUntil = clock.instant().plusSeconds(300);
			return new Claim(row.leaseToken, null);
		});
	}

	Instant verified(UUID submissionId, UUID token, String receipt) {
		return transactions.execute(ignored -> {
			var row = entities.find(LocalSeedPreparationEntity.class, submissionId, LockModeType.PESSIMISTIC_WRITE);
			if (row == null || !token.equals(row.leaseToken) || !row.leaseUntil.isAfter(clock.instant()))
				throw new RunSubmissionException("SEED_PREPARATION_LEASE_LOST", 503);
			row.verifiedAt = clock.instant();
			row.receipt = receipt;
			return row.verifiedAt;
		});
	}

	void workerEvent(UUID operationId, String kind, Object payload) {
		String encoded = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(payload);
		if (encoded.length() > 16384)
			throw new IllegalArgumentException("Seed worker event exceeds its budget");
		transactions.executeWithoutResult(ignored -> entities
			.persist(new LocalSeedWorkerEventEntity(operationId, kind, clock.instant(), encoded)));
	}

	Instant verifiedAt(UUID submissionId) {
		return transactions.execute(ignored -> {
			var row = entities.find(LocalSeedPreparationEntity.class, submissionId);
			if (row == null || row.verifiedAt == null)
				throw new RunSubmissionException("SEED_NOT_OWNED", 503);
			return row.verifiedAt;
		});
	}

}
