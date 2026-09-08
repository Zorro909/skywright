package de.zorro909.skywright.backend.runstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class RunStoreLifecycleTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String RUN = "123e4567-e89b-12d3-a456-426614174000";

	private static final String ATTEMPT = "123e4567-e89b-12d3-a456-426614174001";

	private static final String VERSION = "sha256:" + "9".repeat(64);

	private static final String REFERENCE = "skywright-checkpoint:v1:1:sha256:" + "a".repeat(64);

	@Test
	void readsCompletionWithoutCheckpointPayloadAndKeepsOnlyJournalAttemptOrder() {
		var f = new Fixture();
		var result = f.read();
		assertThat(result.latestAttempt().attemptId()).isEqualTo(ATTEMPT);
		assertThat(result.latestAttempt().cause()).isEqualTo("completed");
		assertThat(result.latestAttempt().checkpointReference()).isEqualTo(REFERENCE);
		assertThat(f.objects.opened).noneMatch(key -> key.contains("/checkpoints/"));
		assertThat(result.recoveryDebt()).isZero();
	}

	@Test
	void missingHeadAndMissingLinkedRecordsFailInsteadOfFabricatingAnEmptyHistory() {
		var f = new Fixture();
		f.objects.values.remove(f.key("recovery/head.json"));
		assertThatThrownBy(f::read).hasMessageContaining("HEAD_MISSING");
		var missing = new Fixture();
		missing.objects.values.remove(missing.key("recovery/events/" + missing.head + ".json"));
		assertThatThrownBy(missing::read).hasMessageContaining("RECORD_MISSING");
		var empty = new Memory();
		assertThat(new RunStoreLifecycle(new RunStoreProtocol("project", RUN), empty).read(VERSION, 1).attempts())
			.isEmpty();
	}

	@Test
	void setupLogsDoNotInventAnAttemptOrHideUnjournaledTrainingRecords() {
		var objects = new Memory();
		var protocol = new RunStoreProtocol("project", RUN);
		for (int i = 0; i < 600; i++)
			objects.put(protocol.runPrefix() + "skypilot/logs/" + i, "run-log-raw",
					"setup".getBytes(StandardCharsets.UTF_8));
		var lifecycle = new RunStoreLifecycle(protocol, objects);
		assertThat(lifecycle.read(VERSION, 1).attempts()).isEmpty();
		objects.put(protocol.progressKey(), "progress-record", "{}".getBytes(StandardCharsets.UTF_8));
		assertThatThrownBy(() -> lifecycle.read(VERSION, 1)).hasMessageContaining("HEAD_MISSING");
		objects.values.remove(protocol.progressKey());
		objects.put(protocol.runPrefix() + "zzz-unrecognized", "unknown", new byte[0]);
		assertThatThrownBy(() -> lifecycle.read(VERSION, 1)).hasMessageContaining("HEAD_MISSING");
	}

	@Test
	void stalledPrestartInventoryIsUnavailableInsteadOfFabricatingAnEmptyHistory() {
		var protocol = new RunStoreProtocol("project", RUN);
		var objects = new Memory() {
			@Override
			public RunStoreObjectPage list(String prefix, int limit, String continuation) {
				return new RunStoreObjectPage(List.of(new RunStoreObjectPage.Entry(prefix + "skypilot/logs/setup", 5)),
						"stuck");
			}
		};
		assertThatThrownBy(() -> new RunStoreLifecycle(protocol, objects).read(VERSION, 1))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("does not advance");
	}

	@Test
	void reportValidationRejectsForeignIdentityUnknownCauseAndIncompleteFinalization() {
		for (var mutation : Map
			.<String, JsonNode>of("runId", JSON.valueToTree("another-run"), "schemaVersion", JSON.valueToTree(2),
					"cause", JSON.valueToTree("preempted"), "lastCommittedStep", JSON.valueToTree(true),
					"latestDurableCheckpoint", JSON.valueToTree("missing"), "latestDurableStep", JSON.valueToTree(2))
			.entrySet()) {
			var f = new Fixture();
			f.report.set(mutation.getKey(), mutation.getValue());
			f.putReport();
			assertThatThrownBy(f::read).as(mutation.getKey()).isInstanceOf(RunStoreIntegrityException.class);
		}
		var missing = new Fixture();
		missing.report.remove("lastCommittedStep");
		missing.putReport();
		assertThatThrownBy(missing::read).hasMessageContaining("lastCommittedStep");
	}

	@Test
	void corruptBytesDuplicateJsonMembersAndOversizedControlRecordsFailClosed() {
		var corrupt = new Fixture();
		corrupt.objects.corrupt = corrupt.key("attempts/" + ATTEMPT + "/report.json");
		assertThatThrownBy(corrupt::read).hasMessageContaining("DIGEST");
		var duplicate = new Fixture();
		duplicate.objects.put(duplicate.key("attempts/" + ATTEMPT + "/report.json"), "execution-termination-report",
				"{\"cause\":\"completed\",\"cause\":\"cancelled\"}".getBytes(StandardCharsets.UTF_8));
		assertThatThrownBy(duplicate::read).hasMessageContaining("DOCUMENT");
		var huge = new Fixture();
		huge.objects.put(huge.key("attempts/" + ATTEMPT + "/report.json"), "execution-termination-report",
				new byte[65537]);
		assertThatThrownBy(huge::read).hasMessageContaining("METADATA");
	}

	@Test
	void aLaterAttemptCannotFollowTerminalEvidenceAndAnExhaustionMustMatchActualDebt() {
		var later = new Fixture();
		var event = later.attemptEvent("123e4567-e89b-12d3-a456-426614174002", later.head, 1);
		event.set("previousWriter", JSON.valueToTree(
				Map.of("run_id", RUN, "attempt_id", ATTEMPT, "condition", "stopped", "reference", "supervisor proof")));
		later.head(later.event(event));
		assertThatThrownBy(later::read).hasMessageContaining("ATTEMPT_AFTER_TERMINAL");
		var exhausted = new Fixture();
		exhausted.objects.put(exhausted.key("recovery/exhaustion.json"), "recovery-record",
				JSON.writeValueAsBytes(exhausted.identity()
					.put("maximumDebt", 1)
					.put("prospectiveDebt", 2)
					.put("historyHead", exhausted.head)));
		assertThatThrownBy(exhausted::read).hasMessageContaining("EXHAUSTION_AFTER_TERMINAL");
	}

	@Test
	void missingReportIsNotAProvenCauseAndUnavailableStorageIsNotAbsence() {
		var missing = new Fixture();
		missing.objects.values.remove(missing.key("attempts/" + ATTEMPT + "/report.json"));
		assertThat(missing.read().latestAttempt().cause()).isNull();
		missing.objects.failure = new IllegalStateException("AccessDenied");
		assertThatThrownBy(missing::read).hasMessage("AccessDenied");
	}

	@Test
	void validatesStartupRefusalAgainstItsExactStopRequestWithoutInventingAttemptHistory() {
		var fixture = new Fixture();
		fixture.objects.values.clear();
		String id = java.util.UUID.randomUUID().toString();
		var request = fixture.identity()
			.put("commandId", id)
			.put("kind", "cancellation")
			.put("requestedAt", "2026-09-07T00:00:00Z");
		var refusal = fixture.identity()
			.put("commandId", id)
			.put("kind", "cancellation")
			.put("refusedAt", "2026-09-07T00:00:01Z");
		fixture.objects.put(fixture.key("control/cancellation.json"), "run-stop-request",
				JSON.writeValueAsBytes(request));
		assertThat(fixture.read().attempts()).isEmpty();
		fixture.objects.put(fixture.key("control/startup-refusal.json"), "run-stop-refusal",
				JSON.writeValueAsBytes(refusal));
		assertThat(fixture.read().stopRefusal().commandId()).isEqualTo(id);
		assertThat(fixture.read().attempts()).isEmpty();
		request.put("commandId", java.util.UUID.randomUUID().toString());
		fixture.objects.put(fixture.key("control/cancellation.json"), "run-stop-request",
				JSON.writeValueAsBytes(request));
		assertThatThrownBy(fixture::read).isInstanceOf(RunStoreIntegrityException.class);
		request.put("commandId", id).put("requestedAt", "invalid-timestamp");
		fixture.objects.put(fixture.key("control/cancellation.json"), "run-stop-request",
				JSON.writeValueAsBytes(request));
		assertThatThrownBy(fixture::read).isInstanceOf(RunStoreIntegrityException.class);
		fixture.objects.values.remove(fixture.key("control/cancellation.json"));
		assertThatThrownBy(fixture::read).isInstanceOf(RunStoreIntegrityException.class);
	}

	private static class Fixture {

		final RunStoreProtocol protocol = new RunStoreProtocol("project", RUN);

		final Memory objects = new Memory();

		final ObjectNode report = identity().put("attemptId", ATTEMPT)
			.put("cause", "completed")
			.put("lastCommittedStep", 1)
			.put("latestDurableStep", 1)
			.put("latestDurableCheckpoint", REFERENCE)
			.set("diagnostics", JSON.createObjectNode());

		String head;

		Fixture() {
			String first = event(attemptEvent(ATTEMPT, null, 0));
			var checkpoint = identity().put("maximumDebt", 1)
				.put("previous", first)
				.put("kind", "checkpoint")
				.put("attemptId", ATTEMPT)
				.put("step", 1)
				.put("reference", REFERENCE);
			head(event(checkpoint));
			var record = identity().put("attemptId", ATTEMPT)
				.putNull("seedCheckpointStep")
				.putNull("seedCheckpointReference")
				.set("rejectedCorruptCheckpoints", JSON.createArrayNode());
			objects.put(key("attempts/" + ATTEMPT + "/record.json"), "execution-attempt-record",
					JSON.writeValueAsBytes(record));
			putReport();
		}

		ObjectNode attemptEvent(String attempt, String previous, int debt) {
			var raw = JSON.createObjectNode()
				.put("attempt_id", attempt)
				.put("run_id", RUN)
				.put("project_version", VERSION)
				.putNull("seed_checkpoint_step")
				.putNull("seed_checkpoint_reference")
				.set("rejected_corrupt_checkpoints", JSON.createArrayNode());
			return identity().put("maximumDebt", 1)
				.put("previous", previous)
				.put("kind", "attempt")
				.put("admittedDebt", debt)
				.putNull("previousWriter")
				.putNull("externalSeed")
				.set("attempt", raw);
		}

		ObjectNode identity() {
			return JSON.createObjectNode().put("schemaVersion", 1).put("runId", RUN).put("projectVersion", VERSION);
		}

		String key(String suffix) {
			return protocol.runPrefix() + suffix;
		}

		void putReport() {
			objects.put(key("attempts/" + ATTEMPT + "/report.json"), "execution-termination-report",
					JSON.writeValueAsBytes(report));
		}

		String event(JsonNode event) {
			byte[] bytes = JSON.writeValueAsBytes(event);
			String digest = hash(bytes);
			objects.put(key("recovery/events/" + digest + ".json"), "recovery-record", bytes);
			return digest;
		}

		void head(String digest) {
			head = digest;
			objects.put(key("recovery/head.json"), "recovery-head",
					JSON.writeValueAsBytes(Map.of("schemaVersion", 1, "digest", digest)));
		}

		RunProcessEvidence read() {
			return new RunStoreLifecycle(protocol, objects).read(VERSION, 1);
		}

	}

	private static class Memory implements RunStoreObjectStore {

		final Map<String, RunStoreObject> values = new HashMap<>();

		final List<String> opened = new ArrayList<>();

		String corrupt;

		RuntimeException failure;

		void put(String key, String kind, byte[] bytes) {
			values.put(key,
					new RunStoreObject(key, bytes, "application/json",
							Map.of("skywright-schema", "v1", "skywright-kind", kind, "skywright-size",
									Integer.toString(bytes.length), "skywright-sha256", hash(bytes))));
		}

		@Override
		public RunStoreContent open(String key) {
			if (failure != null)
				throw failure;
			opened.add(key);
			var value = values.get(key);
			if (value == null)
				return null;
			byte[] bytes = value.bytes();
			var descriptor = new RunStoreObjectMetadata(key, bytes.length, value.contentType(), value.metadata());
			if (key.equals(corrupt))
				bytes[0] ^= 1;
			return new RunStoreContent(descriptor, new ByteArrayInputStream(bytes));
		}

		@Override
		public RunStoreObjectPage list(String prefix, int limit, String continuation) {
			var keys = values.keySet().stream().filter(k -> k.startsWith(prefix)).sorted().toList();
			int start = continuation == null ? 0 : Integer.parseInt(continuation);
			int end = Math.min(start + limit, keys.size());
			return new RunStoreObjectPage(keys.subList(start, end)
				.stream()
				.map(k -> new RunStoreObjectPage.Entry(k, values.get(k).bytes().length))
				.toList(), end < keys.size() ? Integer.toString(end) : null);
		}

		@Override
		public RunStoreObjectMetadata head(String key) {
			throw new UnsupportedOperationException();
		}

		@Override
		public URI presignGet(String key, int seconds, String type, String filename) {
			throw new UnsupportedOperationException();
		}

	}

	private static String hash(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (Exception impossible) {
			throw new IllegalStateException(impossible);
		}
	}

}
