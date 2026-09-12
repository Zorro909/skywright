package de.zorro909.skywright.backend.runsubmission;

import de.zorro909.skywright.backend.trainingproject.ReferencedProjectArtifact;
import de.zorro909.skywright.backend.trainingproject.TrainingProjectArtifactReferences;

import de.zorro909.skywright.backend.orchestration.LaunchDispatchGate;
import de.zorro909.skywright.backend.orchestration.RetainedSkyPilotFact;
import de.zorro909.skywright.backend.orchestration.RetainedSkyPilotFacts;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class RunAcceptanceStore
		implements LaunchDispatchGate, RetainedSkyPilotFacts, TrainingProjectArtifactReferences {

	private final EntityManager entities;

	private final TransactionTemplate transactions;

	private final RunCommandStore commands;

	private final LocalSeedPreparations seeds;

	private final java.time.Clock clock;

	RunAcceptanceStore(EntityManager entities, PlatformTransactionManager transactions, RunCommandStore commands,
			java.time.Clock clock, LocalSeedPreparations seeds) {
		this.seeds = seeds;
		this.entities = entities;
		this.commands = commands;
		this.clock = clock;
		this.transactions = new TransactionTemplate(transactions);
	}

	Optional<AcceptedRun> bySubmission(UUID submissionId) {
		return transactions.execute(ignored -> entities
			.createQuery(
					"select r from RunRecordEntity r where r.principalIdentity = :principal and r.submissionId = :id",
					RunRecordEntity.class)
			.setParameter("principal", "built-in")
			.setParameter("id", submissionId)
			.getResultStream()
			.findFirst()
			.map(RunRecordEntity::view));
	}

	public AcceptedRun get(UUID runId) {
		return transactions.execute(ignored -> {
			var run = entities.find(RunRecordEntity.class, runId);
			if (run == null)
				throw new RunSubmissionException("RUN_NOT_FOUND", 404);
			return run.view();
		});
	}

	public tools.jackson.databind.JsonNode currentStorage(UUID runId) {
		return transactions.execute(ignored -> {
			var location = entities.find(RunStoreLocationEntity.class, runId);
			if (location == null)
				throw new RunSubmissionException("RUN_STORE_LOCATION_UNAVAILABLE", 503);
			return JsonMapper.builder().build().readTree(location.descriptor);
		});
	}

	public record Lineage(UUID runId, boolean available, Instant observedAt, UUID predecessorRunId,
			String checkpointReference, Instant seedVerifiedAt) {
	}

	public Lineage lineage(UUID runId) {
		return transactions.execute(ignored -> {
			if (entities.find(RunRecordEntity.class, runId) == null)
				throw new RunSubmissionException("RUN_NOT_FOUND", 404);
			var row = entities.find(RunLineageEntity.class, runId);
			return row == null ? new Lineage(runId, false, clock.instant(), null, null, null) : new Lineage(runId, true,
					clock.instant(), row.predecessorRunId, row.checkpointReference, row.seedVerifiedAt);
		});
	}

	public boolean dispatchPrevented(UUID runId) {
		return transactions.execute(ignored -> entities.find(RunDispatchPreventionEntity.class, runId) != null);
	}

	public List<UUID> page(UUID after, int limit) {
		if (limit < 1 || limit > 100)
			throw new IllegalArgumentException("Run page limit must be 1..100");
		return transactions.execute(ignored -> {
			var query = entities.createQuery("select r.id from RunRecordEntity r "
					+ (after == null ? "" : "where r.id > :after ") + "order by r.id", UUID.class);
			if (after != null)
				query.setParameter("after", after);
			return query.setMaxResults(limit).getResultList();
		});
	}

	public List<RetainedSkyPilotFact> retainedFacts(UUID runId) {
		return transactions.execute(ignored -> {
			var result = new java.util.ArrayList<RetainedSkyPilotFact>();
			long bytes = 0;
			try (var rows = entities
				.createQuery("select f, max(o.observedAt), o.completeUniqueObservation from SkyPilotFactEntity f "
						+ "join SkyPilotFactObservationEntity o on o.factId=f.id where f.runId=:run group by f, o.completeUniqueObservation",
						Object[].class)
				.setParameter("run", runId)
				.setHint("org.hibernate.fetchSize", 100)
				.getResultStream()) {
				var iterator = rows.iterator();
				while (iterator.hasNext()) {
					var row = iterator.next();
					var fact = (SkyPilotFactEntity) row[0];
					bytes += fact.payload.length();
					if (result.size() >= 100000 || bytes > 16 * 1024 * 1024)
						throw new RunSubmissionException("RETAINED_FACT_READ_BUDGET", 503);
					result.add(new RetainedSkyPilotFact(runId, RetainedSkyPilotFact.Kind.valueOf(fact.kind),
							fact.sourceEventIdentity,
							JsonMapper.builder()
								.build()
								.readValue(fact.payload,
										new tools.jackson.core.type.TypeReference<java.util.Map<String, String>>() {
										}),
							(Instant) row[1], (Boolean) row[2]));
				}
			}
			return List.copyOf(result);
		});
	}

	record Creation(AcceptedRun run, RunAdmission.Prepared prepared) {
	}

	Creation accept(LocalRunRequest request, String requestDigest, RunAdmission admission) {
		UUID seedRunId = request.checkpointSeed() == null ? null : seeds.reserve(request, requestDigest);
		var holder = new RunAdmission.Prepared[1];
		try {
			return transactions.execute(ignored -> {
				UUID id = seedRunId == null ? UUID.randomUUID() : seedRunId;
				var prepared = admission.prepare(id, request);
				holder[0] = prepared;
				var run = new AcceptedRun(id, request.submissionId(), requestDigest,
						clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS), prepared.definition(),
						prepared.task(), prepared.artifacts());
				entities.persist(new RunRecordEntity(run));
				entities.persist(new RunStoreLocationEntity(run));
				entities.persist(new RunLineageEntity(id, request.checkpointSeed(),
						request.checkpointSeed() == null ? null : seeds.verifiedAt(request.submissionId())));
				commands.submission(run);
				entities.flush();
				return new Creation(run, prepared);
			});
		}
		catch (RuntimeException failure) {
			if (holder[0] != null)
				holder[0].close();
			throw failure;
		}
	}

	@Override
	public CompletionStage<Decision> claim(UUID runId, String taskFingerprint) {
		try {
			var result = transactions.execute(ignored -> {
				var run = entities.find(RunRecordEntity.class, runId, LockModeType.PESSIMISTIC_WRITE);
				if (run == null)
					return Decision.UNAVAILABLE;
				if (!run.taskFingerprint.equals(taskFingerprint))
					return Decision.DEFINITION_CONFLICT;
				var previous = entities.find(LaunchClaimEntity.class, runId);
				if (previous != null)
					return previous.fingerprint.equals(taskFingerprint) ? Decision.ALREADY_DISPATCHED
							: Decision.DEFINITION_CONFLICT;
				if (entities.find(RunDispatchPreventionEntity.class, runId) != null)
					return Decision.STOP_REQUESTED;
				entities.persist(new LaunchClaimEntity(runId, taskFingerprint));
				entities.flush();
				return Decision.FIRST_DISPATCH;
			});
			return CompletableFuture.completedFuture(result);
		}
		catch (RuntimeException failure) {
			return CompletableFuture.completedFuture(Decision.UNAVAILABLE);
		}
	}

	@Override
	public CompletionStage<Void> append(List<RetainedSkyPilotFact> facts) {
		try {
			transactions.executeWithoutResult(ignored -> {
				// Serialize each Run's append/compare; sorted locks avoid batch
				// deadlocks.
				facts.stream().map(RetainedSkyPilotFact::runId).distinct().sorted().forEach(run -> {
					if (entities.find(RunRecordEntity.class, run, LockModeType.PESSIMISTIC_WRITE) == null)
						throw new RunSubmissionException("RUN_NOT_FOUND", 404);
				});
				var json = JsonMapper.builder().build();
				for (var fact : facts) {
					var digest = SkyPilotFactEntity.payloadDigest(fact.payload());
					var previous = entities
						.createQuery("select f from SkyPilotFactEntity f where f.runId=:run "
								+ "and f.kind=:kind and f.sourceEventIdentity=:event and f.payloadDigest=:digest",
								SkyPilotFactEntity.class)
						.setParameter("run", fact.runId())
						.setParameter("kind", fact.kind().name())
						.setParameter("event", fact.sourceEventIdentity())
						.setParameter("digest", digest)
						.getResultStream()
						.findFirst();
					SkyPilotFactEntity stored;
					if (previous.isEmpty()) {
						stored = new SkyPilotFactEntity(fact);
						entities.persist(stored);
					}
					else {
						stored = previous.get();
						if (!json.readTree(stored.payload).equals(json.valueToTree(fact.payload())))
							throw new IllegalStateException("Source payload digest conflict");
					}
					var observedAt = fact.observedAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
					var seen = entities.createQuery(
							"select o.id from SkyPilotFactObservationEntity o where o.factId=:fact and o.observedAt=:at and o.completeUniqueObservation=:qualified",
							UUID.class)
						.setParameter("fact", stored.id)
						.setParameter("at", observedAt)
						.setParameter("qualified", fact.completeUniqueObservation())
						.setMaxResults(1)
						.getResultList();
					if (seen.isEmpty())
						entities.persist(new SkyPilotFactObservationEntity(stored.id, observedAt,
								fact.completeUniqueObservation()));
				}
			});
			return CompletableFuture.completedFuture(null);
		}
		catch (RuntimeException failure) {
			return CompletableFuture.failedFuture(failure);
		}
	}

	@Override
	public java.util.Set<ReferencedProjectArtifact> referencedArtifacts(UUID projectId) {
		return transactions.execute(ignored -> {
			var result = new java.util.HashSet<ReferencedProjectArtifact>();
			try (var documents = entities.createQuery(
					"select r.artifactReferences from RunRecordEntity r where r.projectIdentity=:project order by r.id",
					String.class)
				.setParameter("project", projectId.toString())
				.setHint("org.hibernate.fetchSize", 100)
				.getResultStream()) {
				documents.forEach(document -> result.addAll(JsonMapper.builder()
					.build()
					.readValue(document,
							new tools.jackson.core.type.TypeReference<java.util.Set<ReferencedProjectArtifact>>() {
							})));
			}
			return java.util.Set.copyOf(result);
		});
	}

}
