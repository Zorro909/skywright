package de.zorro909.skywright.backend.runstore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads the SDK's hash-linked admission order and immutable terminal evidence. */
public final class RunStoreLifecycle {

	private static final JsonMapper JSON = JsonMapper.builder()
		.enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
		.build();

	private static final Set<String> CAUSES = Set.of("completed", "interrupted", "cancelled", "policy_stopped",
			"contract_violation", "training_project_failure", "skywright_failure");

	private final RunStoreProtocol protocol;

	private final RunStoreObjectStore objects;

	public RunStoreLifecycle(RunStoreProtocol protocol, RunStoreObjectStore objects) {
		this.protocol = protocol;
		this.objects = objects;
	}

	public RunProcessEvidence read(String projectVersion, int maximumDebt) {
		if (maximumDebt < 1)
			throw invalid("POLICY");
		return new Read(projectVersion, maximumDebt).read();
	}

	private final class Read {

		private final String version;

		private final int maximumDebt;

		private int bytesRead;

		Read(String version, int maximumDebt) {
			this.version = version;
			this.maximumDebt = maximumDebt;
		}

		RunProcessEvidence read() {
			var refusal = stopRefusal();
			var head = document("recovery/head.json", "recovery-head", 65536, false);
			if (head == null) {
				if (!emptyTrainingStore())
					throw invalid("HEAD_MISSING");
				return new RunProcessEvidence(List.of(), null, 0, null, refusal);
			}
			schema(head);
			if (head.size() != 2)
				throw invalid("HEAD_FIELDS");
			String headDigest = digest(head.path("digest"));
			String next = headDigest;
			var events = new ArrayList<JsonNode>();
			var seen = new HashSet<String>();
			int historyBytes = 0;
			while (next != null) {
				if (!seen.add(next) || seen.size() > 100000)
					throw invalid("HISTORY_LIMIT");
				int before = bytesRead;
				var event = document("recovery/events/" + next + ".json", "recovery-record", 65536, true);
				historyBytes += bytesRead - before;
				if (historyBytes > 16 * 1024 * 1024)
					throw invalid("HISTORY_LIMIT");
				identity(event);
				if (number(event, "maximumDebt", 1) != maximumDebt || !event.has("previous"))
					throw invalid("HISTORY_POLICY");
				next = event.path("previous").isNull() ? null : digest(event.path("previous"));
				events.add(event);
			}
			Collections.reverse(events);
			var attempts = new ArrayList<RunProcessEvidence.Attempt>();
			var checkpoints = new LinkedHashMap<Long, String>();
			var reportCheckpoints = new LinkedHashMap<Long, String>();
			JsonNode pinnedSeed = null;
			var attemptIds = new HashSet<String>();
			long debt = 0;
			String active = null;
			for (var event : events) {
				switch (text(event, "kind")) {
					case "attempt" -> {
						var raw = event.path("attempt");
						String id = text(raw, "attempt_id");
						try {
							if (!UUID.fromString(id).toString().equals(id))
								throw invalid("ATTEMPT_ID");
						}
						catch (IllegalArgumentException failure) {
							throw invalid("ATTEMPT_ID");
						}
						if (!attemptIds.add(id) || !protocol.runId().equals(text(raw, "run_id"))
								|| !version.equals(text(raw, "project_version")))
							throw invalid("ATTEMPT_IDENTITY");
						if (active != null) {
							var previous = report(active, reportCheckpoints);
							attempts.set(attempts.size() - 1, previous);
							if (previous.cause() != null && !previous.cause().equals("interrupted"))
								throw invalid("ATTEMPT_AFTER_TERMINAL");
							var proof = event.path("previousWriter");
							if (!protocol.runId().equals(text(proof, "run_id"))
									|| !active.equals(text(proof, "attempt_id"))
									|| !Set.of("stopped", "write-authority-revoked").contains(text(proof, "condition")))
								throw invalid("PREVIOUS_WRITER");
							text(proof, "reference");
							debt++;
						}
						else if (!event.path("previousWriter").isNull())
							throw invalid("PREVIOUS_WRITER");
						if (debt > maximumDebt || number(event, "admittedDebt", 0) != debt)
							throw invalid("ADMISSION_DEBT");
						var seed = event.path("externalSeed");
						if (active == null)
							pinnedSeed = seed;
						if (!seed.isNull()) {
							if (seed.size() != 4 || !seed.equals(pinnedSeed) || !checkpoints.isEmpty()
									|| protocol.runId().equals(text(seed, "source_run_id"))
									|| !seed.path("ordering_reset").isBoolean())
								throw invalid("EXTERNAL_SEED");
							long step = number(seed, "step", 1);
							String reference = text(seed, "reference");
							checkpoint(step, reference);
							if (number(raw, "seed_checkpoint_step", 1) != step
									|| !reference.equals(text(raw, "seed_checkpoint_reference")))
								throw invalid("ATTEMPT_SEED");
							reportCheckpoints.put(step, reference);
						}
						else if (!checkpoints.isEmpty()) {
							long step = number(raw, "seed_checkpoint_step", 1);
							if (!text(raw, "seed_checkpoint_reference").equals(checkpoints.get(step)))
								throw invalid("ATTEMPT_SEED");
						}
						else if (pinnedSeed == null || !pinnedSeed.isNull()
								|| !raw.path("seed_checkpoint_step").isNull()
								|| !raw.path("seed_checkpoint_reference").isNull())
							throw invalid("ATTEMPT_SEED");
						var record = document("attempts/" + id + "/record.json", "execution-attempt-record", 65536,
								true);
						identity(record);
						if (raw.size() != 6 || record.size() != 7 || !raw.path("rejected_corrupt_checkpoints").isArray()
								|| !record.path("rejectedCorruptCheckpoints")
									.equals(raw.path("rejected_corrupt_checkpoints"))
								|| !id.equals(text(record, "attemptId"))
								|| !record.path("seedCheckpointStep").equals(raw.path("seed_checkpoint_step"))
								|| !record.path("seedCheckpointReference")
									.equals(raw.path("seed_checkpoint_reference")))
							throw invalid("ATTEMPT_RECORD");
						// Reports are validated after all checkpoint events of their
						// attempt.
						attempts.add(new RunProcessEvidence.Attempt(id, null, null, null, null));
						active = id;
					}
					case "checkpoint" -> {
						long step = number(event, "step", 1);
						String reference = text(event, "reference");
						checkpoint(step, reference);
						if (active == null || !active.equals(text(event, "attemptId"))
								|| checkpoints.putIfAbsent(step, reference) != null)
							throw invalid("CHECKPOINT_EVENT");
						reportCheckpoints.put(step, reference);
						debt = Math.max(0, debt - 1);
					}
					default -> throw invalid("EVENT_KIND");
				}
			}
			if (attempts.isEmpty())
				throw invalid("ATTEMPT_MISSING");
			// Journal order, never UUID or fetch-time order, identifies the latest
			// process.
			attempts.set(attempts.size() - 1, report(active, reportCheckpoints));
			Instant exhaustedAt = null;
			var exhaustion = document("recovery/exhaustion.json", "recovery-record", 16 * 1024 * 1024, false);
			if (exhaustion != null) {
				identity(exhaustion);
				var last = attempts.getLast();
				if (last.cause() != null && !last.cause().equals("interrupted"))
					throw invalid("EXHAUSTION_AFTER_TERMINAL");
				if (number(exhaustion, "maximumDebt", 1) != maximumDebt
						|| number(exhaustion, "prospectiveDebt", 1) != debt + 1 || debt + 1 <= maximumDebt
						|| !headDigest.equals(text(exhaustion, "historyHead")))
					throw invalid("EXHAUSTION_POLICY");
				var prior = exhaustion.path("priorAttempts");
				if (!prior.isArray() || prior.size() != attempts.size())
					throw invalid("EXHAUSTION_ATTEMPTS");
				for (int i = 0; i < attempts.size(); i++)
					if (!attempts.get(i).attemptId().equals(prior.get(i).asText()))
						throw invalid("EXHAUSTION_ATTEMPTS");
				var durable = exhaustion.path("latestDurableSafePoint");
				if (checkpoints.isEmpty()) {
					if (!durable.isNull())
						throw invalid("EXHAUSTION_CHECKPOINT");
				}
				else {
					long step = Collections.max(checkpoints.keySet());
					if (number(durable, "step", 1) != step || !checkpoints.get(step).equals(text(durable, "reference")))
						throw invalid("EXHAUSTION_CHECKPOINT");
				}
				double time = exhaustion.path("exhaustedAt").asDouble(Double.NaN);
				if (!exhaustion.path("exhaustedAt").isNumber() || !Double.isFinite(time) || time <= 0
						|| time > 253402300799.)
					throw invalid("EXHAUSTION_TIME");
				exhaustedAt = Instant.ofEpochSecond((long) time, (long) ((time - (long) time) * 1_000_000_000));
			}
			if (!head.equals(document("recovery/head.json", "recovery-head", 65536, true)))
				throw invalid("HISTORY_CHANGED");
			return new RunProcessEvidence(attempts, headDigest, (int) debt, exhaustedAt, refusal);
		}

		private RunProcessEvidence.StopRefusal stopRefusal() {
			var value = document("control/startup-refusal.json", "run-stop-refusal", 65536, false);
			if (value == null)
				return null;
			identity(value);
			String kind = text(value, "kind"), command = text(value, "commandId");
			if (value.size() != 6 || !Set.of("cancellation", "policy-stop").contains(kind))
				throw invalid("STOP_REFUSAL");
			try {
				if (!UUID.fromString(command).toString().equals(command))
					throw invalid("STOP_REFUSAL");
			}
			catch (IllegalArgumentException failure) {
				throw invalid("STOP_REFUSAL");
			}
			var request = document("control/" + kind + ".json", "run-stop-request", 65536, true);
			identity(request);
			if (request.size() != 6 || !kind.equals(text(request, "kind"))
					|| !command.equals(text(request, "commandId")))
				throw invalid("STOP_REFUSAL");
			try {
				Instant.parse(text(request, "requestedAt"));
				return new RunProcessEvidence.StopRefusal(command, kind, Instant.parse(text(value, "refusedAt")));
			}
			catch (java.time.DateTimeException failure) {
				throw invalid("STOP_REFUSAL");
			}
		}

		private boolean emptyTrainingStore() {
			var prefix = protocol.runPrefix();
			var controls = Set.of(prefix + "control/cancellation.json", prefix + "control/policy-stop.json",
					prefix + "control/startup-refusal.json");
			String continuation = null;
			for (int i = 0; i < 64; i++) {
				var page = objects.list(prefix, 256, continuation);
				if (page.entries()
					.stream()
					.anyMatch(e -> !controls.contains(e.key()) && !e.key().startsWith(prefix + "skypilot/logs/")))
					return false;
				if (page.continuation() == null)
					return true;
				if (page.continuation().equals(continuation))
					break;
				continuation = page.continuation();
			}
			throw new IllegalStateException("Pre-start inventory exceeds its read budget or does not advance");
		}

		private RunProcessEvidence.Attempt report(String id, Map<Long, String> checkpoints) {
			var value = document("attempts/" + id + "/report.json", "execution-termination-report", 65536, false);
			if (value == null)
				return new RunProcessEvidence.Attempt(id, null, null, null, null);
			identity(value);
			String cause = text(value, "cause");
			if (!id.equals(text(value, "attemptId")) || !CAUSES.contains(cause))
				throw invalid("REPORT_IDENTITY");
			long committed = number(value, "lastCommittedStep", 0);
			Long durable = value.path("latestDurableStep").isNull() ? null : number(value, "latestDurableStep", 1);
			String reference = value.path("latestDurableCheckpoint").isNull() ? null
					: text(value, "latestDurableCheckpoint");
			if ((durable == null) != (reference == null)
					|| durable != null && (durable > committed || !reference.equals(checkpoints.get(durable))))
				throw invalid("REPORT_CHECKPOINT");
			if (Set.of("completed", "interrupted", "policy_stopped").contains(cause)
					&& (committed < 1 || durable == null || durable != committed))
				throw invalid("REPORT_FINALIZATION");
			if (!value.path("diagnostics").isObject())
				throw invalid("REPORT_DIAGNOSTICS");
			return new RunProcessEvidence.Attempt(id, cause, committed, durable, reference);
		}

		private void identity(JsonNode value) {
			schema(value);
			if (!protocol.runId().equals(text(value, "runId")) || !version.equals(text(value, "projectVersion")))
				throw invalid("IDENTITY");
		}

		private JsonNode document(String suffix, String kind, int limit, boolean required) {
			String key = protocol.runPrefix() + suffix;
			try (var content = open(key)) {
				if (content == null) {
					if (required)
						throw invalid("RECORD_MISSING");
					return null;
				}
				var metadata = content.descriptor();
				if (!key.equals(metadata.key()) || metadata.size() < 0 || metadata.size() > limit
						|| !Long.toString(metadata.size()).equals(metadata.metadata().get("skywright-size"))
						|| !kind.equals(metadata.metadata().get("skywright-kind"))
						|| !"v1".equals(metadata.metadata().get("skywright-schema")))
					throw invalid("METADATA");
				byte[] bytes = content.stream().readNBytes(limit + 1);
				bytesRead = Math.addExact(bytesRead, bytes.length);
				if (bytesRead > 64 * 1024 * 1024 || bytes.length != metadata.size())
					throw invalid("READ_BUDGET");
				String hash;
				try {
					hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
				}
				catch (java.security.NoSuchAlgorithmException impossible) {
					throw new IllegalStateException(impossible);
				}
				if (!hash.equals(metadata.metadata().get("skywright-sha256"))
						|| suffix.startsWith("recovery/events/") && !suffix.equals("recovery/events/" + hash + ".json"))
					throw invalid("DIGEST");
				JsonNode value;
				try {
					value = JSON.readTree(bytes);
				}
				catch (tools.jackson.core.JacksonException failure) {
					throw invalid("DOCUMENT");
				}
				if (value == null || !value.isObject())
					throw invalid("DOCUMENT");
				content.accept();
				return value;
			}
			catch (IOException failure) {
				throw new UncheckedIOException(failure);
			}
		}

	}

	private RunStoreContent open(String key) {
		try {
			return objects.open(key);
		}
		catch (RuntimeException failure) {
			for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
				if (cause instanceof software.amazon.awssdk.services.s3.model.NoSuchKeyException)
					return null;
				if (cause instanceof software.amazon.awssdk.services.s3.model.S3Exception s3 && s3.statusCode() == 404
						&& s3.awsErrorDetails() != null && "NoSuchKey".equals(s3.awsErrorDetails().errorCode()))
					return null;
			}
			throw failure;
		}
	}

	private static String digest(JsonNode value) {
		if (!value.isString() || !value.asText().matches("[0-9a-f]{64}"))
			throw invalid("HISTORY_DIGEST");
		return value.asText();
	}

	private static String text(JsonNode value, String field) {
		var member = value.path(field);
		if (!member.isString() || member.asText().isBlank())
			throw invalid("FIELD_" + field);
		return member.asText();
	}

	private static long number(JsonNode value, String field, long minimum) {
		var member = value.path(field);
		if (!member.isIntegralNumber() || !member.canConvertToLong() || member.asLong() < minimum)
			throw invalid("FIELD_" + field);
		return member.asLong();
	}

	private static void schema(JsonNode value) {
		if (number(value, "schemaVersion", 1) != 1)
			throw invalid("SCHEMA");
	}

	private static void checkpoint(long step, String reference) {
		try {
			if (CheckpointReference.parse(reference).step() != step)
				throw invalid("CHECKPOINT_REFERENCE");
		}
		catch (IllegalArgumentException failure) {
			throw invalid("CHECKPOINT_REFERENCE");
		}
	}

	private static RunStoreIntegrityException invalid(String detail) {
		return new RunStoreIntegrityException("RUN_STORE_LIFECYCLE_" + detail);
	}

}
