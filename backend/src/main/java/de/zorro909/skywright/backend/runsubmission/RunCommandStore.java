package de.zorro909.skywright.backend.runsubmission;

import de.zorro909.skywright.backend.runlifecycle.RunControlDecisions;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import tools.jackson.databind.json.JsonMapper;

/** Atomic command identity and bounded worker leases survive process replacement. */
@Service
@Transactional
public class RunCommandStore implements RunControlDecisions {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final EntityManager entities;

	private final Clock clock;

	RunCommandStore(EntityManager entities, Clock clock) {
		this.entities = entities;
		this.clock = clock;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	void submission(AcceptedRun run) {
		persist(new RunCommandEntity(run.submissionId(), run.runId(), RunCommand.Kind.SUBMISSION, run.acceptedAt(),
				"{}"));
	}

	public RunCommand accept(UUID runId, UUID commandId, RunCommand.Kind kind, String evidence) {
		if (kind == RunCommand.Kind.SUBMISSION || commandId == null || evidence == null || evidence.length() > 65536)
			throw new RunSubmissionException("RUN_COMMAND_INVALID", 422);
		// Lock the Run before comparing identities or creating its unique kind.
		if (entities.find(RunRecordEntity.class, runId, LockModeType.PESSIMISTIC_WRITE) == null)
			throw new RunSubmissionException("RUN_NOT_FOUND", 404);
		var prior = entities.find(RunCommandEntity.class, commandId);
		if (prior != null) {
			if (!prior.runId.equals(runId) || !prior.kind.equals(kind.name())
					|| !JSON.readTree(prior.evidence).equals(JSON.readTree(evidence)))
				throw new RunSubmissionException("RUN_COMMAND_IDENTITY_CONFLICT", 409);
			return view(prior);
		}
		if (!entities.createQuery("select c.id from RunCommandEntity c where c.runId=:run and c.kind=:kind", UUID.class)
			.setParameter("run", runId)
			.setParameter("kind", kind.name())
			.setMaxResults(1)
			.getResultList()
			.isEmpty())
			throw new RunSubmissionException("RUN_COMMAND_ALREADY_EXISTS", 409);
		var command = new RunCommandEntity(commandId, runId, kind,
				clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS), evidence);
		persist(command);
		if (entities.find(LaunchClaimEntity.class, runId) == null
				&& entities.find(RunDispatchPreventionEntity.class, runId) == null)
			entities.persist(new RunDispatchPreventionEntity(command));
		return view(command);
	}

	private void persist(RunCommandEntity command) {
		entities.persist(command);
		entities.persist(new RunCommandDeliveryEntity(command));
		entities.flush();
	}

	@Transactional(readOnly = true)
	public RunCommand get(UUID runId, UUID id) {
		var command = entities.find(RunCommandEntity.class, id);
		if (command == null || !command.runId.equals(runId))
			throw new RunSubmissionException("RUN_COMMAND_NOT_FOUND", 404);
		return view(command);
	}

	private RunCommand view(RunCommandEntity command) {
		return entities.find(RunCommandDeliveryEntity.class, command.id).view(command);
	}

	@Transactional(readOnly = true)
	public List<UUID> due(int limit) {
		if (limit < 1 || limit > 32)
			throw new IllegalArgumentException("Command batch limit must be 1..32");
		return entities
			.createQuery(
					"select d.commandId from RunCommandDeliveryEntity d where d.nextAttemptAt<=:now "
							+ "and (d.leaseUntil is null or d.leaseUntil<=:now) order by d.nextAttemptAt,d.commandId",
					UUID.class)
			.setParameter("now", clock.instant())
			.setMaxResults(limit)
			.getResultList();
	}

	public RunCommand claim(UUID id) {
		var delivery = entities.find(RunCommandDeliveryEntity.class, id, LockModeType.PESSIMISTIC_WRITE);
		var now = clock.instant();
		if (delivery == null || delivery.nextAttemptAt == null || delivery.nextAttemptAt.isAfter(now)
				|| delivery.leaseUntil != null && delivery.leaseUntil.isAfter(now))
			return null;
		delivery.lease = UUID.randomUUID();
		delivery.leaseUntil = now.plusSeconds(60);
		delivery.attempts++;
		return delivery.view(entities.find(RunCommandEntity.class, id));
	}

	public RunCommand projected(RunCommand command, Instant publishedAt) {
		var delivery = owned(command);
		if (delivery == null)
			return null;
		if (delivery.projectedAt == null) {
			delivery.projectedAt = publishedAt;
			if (command.kind() == RunCommand.Kind.CEILING_STOP)
				delivery.forceAfter = publishedAt.plusSeconds(30);
		}
		return delivery.view(entities.find(RunCommandEntity.class, command.id()));
	}

	public void finish(RunCommand command, String disposition, boolean reconcileAgain) {
		if (!List
			.of("projection-delivered", "delivery-unavailable", "handoff-uncertain", "source-observed",
					"force-accepted", "force-uncertain", "effect-observed", "no-stop-effected", "dispatch-prevented",
					"source-accepted")
			.contains(disposition))
			throw new IllegalArgumentException("Unknown command delivery disposition");
		var delivery = owned(command);
		if (delivery == null)
			return;
		delivery.disposition = disposition;
		delivery.nextAttemptAt = reconcileAgain ? clock.instant().plusSeconds(2) : null;
		delivery.lease = null;
		delivery.leaseUntil = null;
	}

	private RunCommandDeliveryEntity owned(RunCommand command) {
		var delivery = entities.find(RunCommandDeliveryEntity.class, command.id(), LockModeType.PESSIMISTIC_WRITE);
		return delivery != null && command.lease() != null && command.lease().equals(delivery.lease) ? delivery : null;
	}

	@Transactional(readOnly = true)
	public boolean submissionClaimed(UUID runId) {
		return entities.find(LaunchClaimEntity.class, runId) != null;
	}

	@Override
	@Transactional(readOnly = true)
	public List<Decision> read(UUID runId) {
		var prevention = entities.find(RunDispatchPreventionEntity.class, runId);
		return entities.createQuery(
				"select c from RunCommandEntity c where c.runId=:run and c.kind<>'SUBMISSION' order by c.acceptedAt,c.id",
				RunCommandEntity.class)
			.setParameter("run", runId)
			.getResultStream()
			.map(c -> new Decision(c.id, Kind.valueOf(c.kind),
					c.kind.equals("CEILING_STOP") ? Instant.parse(JSON.readTree(c.evidence).path("decidedAt").asText())
							: c.acceptedAt,
					prevention != null && prevention.commandId.equals(c.id)))
			.toList();
	}

}
