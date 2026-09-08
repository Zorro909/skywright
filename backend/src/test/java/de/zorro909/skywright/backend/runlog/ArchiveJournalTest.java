package de.zorro909.skywright.backend.runlog;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ArchiveJournalTest {

	static final UUID RUN = UUID.randomUUID();
	static final String VERSION = "project-v1";
	static final Instant NOW = Instant.parse("2026-09-07T10:00:00Z");
	static final JsonMapper JSON = JsonMapper.builder().build();

	static final class Objects implements ArchiveObjects {

		final TreeMap<String, byte[]> values = new TreeMap<>();

		String failAfter;

		public byte[] read(String key, int limit) {
			var result = values.get(key);
			if (result != null && result.length > limit)
				throw new IllegalStateException("bound");
			return result == null ? null : result.clone();
		}

		public byte[] publish(String key, byte[] bytes, String kind) {
			values.putIfAbsent(key, bytes.clone());
			if (key.equals(failAfter)) {
				failAfter = null;
				throw new IllegalStateException("lost acknowledgement");
			}
			return read(key, RunLogArchive.CHUNK_BYTES);
		}

		public List<String> keysAfter(String prefix, String after, int limit) {
			return values.keySet()
				.stream()
				.filter(k -> k.startsWith(prefix) && (after == null || k.compareTo(after) > 0))
				.limit(limit)
				.toList();
		}

		public void close() {
		}

	}

	@Test
	void recoversIndexAndNavigationAfterPublicationAcknowledgementIsLost() {
		var objects = new Objects();
		var journal = new ArchiveJournal(objects, RUN, VERSION);
		var archive = new RunLogArchive(RUN, VERSION);
		String attempt = UUID.randomUUID().toString();
		objects.values.put("attempts/" + attempt + "/record.json", JSON.writeValueAsBytes(java.util.Map
			.of("schemaVersion", 1, "runId", RUN.toString(), "projectVersion", VERSION, "attemptId", attempt)));
		byte[] raw = ("setup\r\n\036SKYWRIGHT_ATTEMPT_V1 "
				+ new String(objects.values.firstEntry().getValue(), StandardCharsets.UTF_8) + "\037\ntrain\u001b[0m\r")
			.getBytes(StandardCharsets.UTF_8);
		var append = archive.append("task", RunLogArchive.Cursor.initial(),
				new RunLogArchive.Page("pod:1", 0, raw, "{}", true, true, null), true, NOW, journal::confirms);
		objects.failAfter = ArchiveJournal.indexKey("task", 0);
		assertThatThrownBy(() -> journal.append(append)).hasMessage("lost acknowledgement");
		var restarted = new ArchiveJournal(objects, RUN, VERSION);
		var cursor = restarted.recover("task", RunLogArchive.Cursor.initial());
		assertThat(cursor).isEqualTo(append.next());
		assertThat(restarted.recover("task", cursor)).isEqualTo(cursor);
		assertThat(objects.read(ArchiveJournal.rawKey(append.chunk()), RunLogArchive.CHUNK_BYTES)).isEqualTo(raw);
		assertThat(objects.read(ArchiveJournal.ROOT + "navigation/" + attempt + ".json", 4096)).isNotNull();
		assertThat(restarted.confirms(attempt)).as("the same marker cannot index another generation").isFalse();
	}

	@Test
	void finalManifestWinsAfterRestartAndClosesSourceReads() {
		var objects = new Objects();
		RunLogSource source = (run, stream, prior) -> new RunLogArchive.Page(stream, 0,
				(stream + "\r\n").getBytes(StandardCharsets.UTF_8), "{}", true, true, null);
		var service = service(source, NOW);
		var journal = new ArchiveJournal(objects, RUN, VERSION);
		var saved = service.capture(RUN, VERSION, RunLogCheckpoint.initial(), journal, true, false);
		assertThat(saved.manifestDigest()).isNotNull();
		var restarted = service((r, s, c) -> {
			throw new AssertionError("archive is sole authority");
		}, NOW.plusSeconds(20));
		var recovered = restarted.capture(RUN, VERSION, RunLogCheckpoint.initial(), journal, true, false);
		assertThat(recovered).isEqualTo(saved);
		assertThat(journal.manifest().task().status()).isEqualTo("complete");
	}

	@Test
	void terminalRetriesAndDeadlineSurviveCursorSerialization() {
		var objects = new Objects();
		var journal = new ArchiveJournal(objects, RUN, VERSION);
		var service = service((r, s, c) -> {
			throw new RunLogSource.Unavailable("SOURCE_LOST");
		}, NOW);
		var cursor = RunLogCheckpoint.initial();
		for (int i = 0; i < 20; i++)
			cursor = service.capture(RUN, VERSION, cursor, journal, false, false).checkpoint();
		assertThat(cursor.task().finalFailures()).isZero();
		for (int i = 0; i < 3; i++) {
			cursor = JSON.readValue(JSON.writeValueAsBytes(cursor), RunLogCheckpoint.class);
			cursor = service.capture(RUN, VERSION, cursor, journal, true, false).checkpoint();
		}
		assertThat(journal.manifest().task().reason()).isEqualTo("SOURCE_LOST");
		assertThat(cursor.terminalAt()).isEqualTo(NOW);
		var empty = new ArchiveJournal(new Objects(), RUN, VERSION);
		var overdue = service((r, s, c) -> {
			throw new AssertionError("expired source must not be polled");
		}, NOW.plusSeconds(301));
		overdue.capture(RUN, VERSION, RunLogCheckpoint.initial().terminal(NOW), empty, true, false);
		assertThat(empty.manifest().controller().reason()).isEqualTo("FINALIZATION_DEADLINE");
	}

	@Test
	void setupFailureCanBeCompleteButMissingDurableAttemptMarkerIsPartial() {
		for (boolean attemptExists : List.of(false, true)) {
			var objects = new Objects();
			if (attemptExists)
				objects.values.put("attempts/" + UUID.randomUUID() + "/record.json", new byte[0]);
			var journal = new ArchiveJournal(objects, RUN, VERSION);
			service((r, s, c) -> new RunLogArchive.Page(s, 0, "setup failed\r\n".getBytes(StandardCharsets.UTF_8), "{}",
					true, true, null), NOW)
				.capture(RUN, VERSION, RunLogCheckpoint.initial(), journal, true, false);
			assertThat(journal.manifest().task().status()).isEqualTo(attemptExists ? "partial" : "complete");
			assertThat(journal.manifest().controller().status()).isEqualTo("complete");
		}
	}

	@Test
	void recoveringOlderManifestRetainsTheDigestOfItsOriginalBytes() {
		var objects = new Objects();
		var journal = new ArchiveJournal(objects, RUN, VERSION);
		service((r, s, c) -> new RunLogArchive.Page(s, 0, "setup".getBytes(StandardCharsets.UTF_8), "{}", true, true,
				null), NOW)
			.capture(RUN, VERSION, RunLogCheckpoint.initial(), journal, true, false);
		var older = (tools.jackson.databind.node.ObjectNode) JSON.readTree(objects.values.get(ArchiveJournal.MANIFEST));
		var checkpoint = (tools.jackson.databind.node.ObjectNode) older.path("checkpoint");
		checkpoint.remove("taskSourceFailure");
		checkpoint.remove("controllerSourceFailure");
		byte[] original = JSON.writeValueAsBytes(older);
		objects.values.put(ArchiveJournal.MANIFEST, original);
		var recovered = service((r, s, c) -> {
			throw new AssertionError("finalized archive must not query source");
		}, NOW.plusSeconds(20)).capture(RUN, VERSION, RunLogCheckpoint.initial(), journal, true, false);
		assertThat(recovered.manifestDigest()).isEqualTo(RunLogArchive.digest(original));
		var reader = new ArchiveReader(objects, RUN, VERSION, recovered.manifestDigest());
		assertThat(reader.read("task", 0L, null, recovered.checkpoint(), NOW).archiveState()).isEqualTo("finalized");
		assertThat(objects.values.get(ArchiveJournal.MANIFEST)).isEqualTo(original);
	}

	private static RunLogArchives service(RunLogSource source, Instant now) {
		return new RunLogArchives(null, null, null, source, Clock.fixed(now, ZoneOffset.UTC));
	}

}
