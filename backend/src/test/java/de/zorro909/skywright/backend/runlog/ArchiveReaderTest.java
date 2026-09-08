package de.zorro909.skywright.backend.runlog;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class ArchiveReaderTest {

	private final ArchiveJournalTest.Objects objects = new ArchiveJournalTest.Objects();

	private final ArchiveJournal journal = new ArchiveJournal(objects, ArchiveJournalTest.RUN,
			ArchiveJournalTest.VERSION);

	private final RunLogArchive archive = new RunLogArchive(ArchiveJournalTest.RUN, ArchiveJournalTest.VERSION);

	private RunLogCheckpoint checkpoint = RunLogCheckpoint.initial();

	@Test
	void pagesRawBytesAcrossChunkBoundariesAndRestartsWithIndependentCursors() {
		byte[] raw = "setup\r\n\033[31m€🙂\033[0m\rrewritten\n".getBytes(StandardCharsets.UTF_8);
		for (int offset = 0; offset < raw.length; offset++)
			append("task", new byte[] { raw[offset] });
		append("controller", new byte[] { 0, (byte) 255, 13, 10 });
		var replay = new java.io.ByteArrayOutputStream();
		long cursor = 0;
		while (cursor < raw.length) {
			var page = reader().read("task", cursor, null, checkpoint, ArchiveJournalTest.NOW);
			assertThat(page.fromCursor()).isEqualTo(Long.toString(cursor));
			replay.writeBytes(Base64.getDecoder().decode(page.bytesBase64()));
			cursor = Long.parseLong(page.nextCursor());
			assertThat(page.completion()).isEqualTo("pending");
		}
		assertThat(replay.toByteArray()).isEqualTo(raw);
		assertThat(reader().read("task", 4L, null, checkpoint, ArchiveJournalTest.NOW))
			.isEqualTo(reader().read("task", 4L, null, checkpoint, ArchiveJournalTest.NOW));
		assertThat(reader().read("controller", 0L, null, checkpoint, ArchiveJournalTest.NOW).endCursor())
			.isEqualTo("4");
		var tail = reader().read("task", null, null, checkpoint, ArchiveJournalTest.NOW);
		assertThat(tail.nextCursor()).isEqualTo(Integer.toString(raw.length));
		assertThat(Base64.getDecoder().decode(tail.bytesBase64()))
			.isEqualTo(Arrays.copyOfRange(raw, raw.length - 4, raw.length));
		var older = reader().read("task", null, Long.parseLong(tail.fromCursor()), checkpoint, ArchiveJournalTest.NOW);
		assertThat(older.nextCursor()).isEqualTo(tail.fromCursor());
	}

	@Test
	void largeChunksHaveBoundedTailAndExactAbsoluteRanges() {
		byte[] raw = new byte[RunLogArchive.CHUNK_BYTES];
		new java.util.Random(234).nextBytes(raw);
		append("controller", raw);
		var tail = reader().read("controller", null, null, checkpoint, ArchiveJournalTest.NOW);
		assertThat(Base64.getDecoder().decode(tail.bytesBase64())).hasSize(ArchiveReader.PAGE_BYTES)
			.isEqualTo(Arrays.copyOfRange(raw, raw.length - ArchiveReader.PAGE_BYTES, raw.length));
		assertThatThrownBy(
				() -> reader().read("controller", (long) raw.length + 1, null, checkpoint, ArchiveJournalTest.NOW))
			.isInstanceOf(IllegalArgumentException.class);
		var chunk = journal.chunk("controller", 0);
		objects.values.put(ArchiveJournal.rawKey(chunk), new byte[] { 1 });
		assertThatThrownBy(() -> reader().read("controller", 0L, null, checkpoint, ArchiveJournalTest.NOW))
			.hasMessage("ARCHIVE_CHUNK_INVALID");
	}

	@Test
	void sourceOutageRetainsBytesAndManifestAloneEndsFollow() {
		append("task", "captured".getBytes(StandardCharsets.UTF_8));
		checkpoint = checkpoint.sourceFailure("task", "COLLECTOR_UNAVAILABLE");
		var stale = reader().read("task", null, null, checkpoint, ArchiveJournalTest.NOW.plusSeconds(30));
		assertThat(stale.sourceAvailability()).isEqualTo("unavailable");
		assertThat(stale.sourceReason()).isEqualTo("COLLECTOR_UNAVAILABLE");
		assertThat(stale.lastSuccessfulFetch()).isEqualTo(ArchiveJournalTest.NOW);
		assertThat(stale.completion()).isEqualTo("pending");
		checkpoint = checkpoint.with("task", RunLogArchive.partial(checkpoint.task(), "SOURCE_LOST"))
			.with("controller", RunLogArchive.partial(checkpoint.controller(), "SOURCE_LOST"))
			.markers(null, true);
		assertThat(reader().read("task", 0L, null, checkpoint, ArchiveJournalTest.NOW).archiveState())
			.isEqualTo("staging");
		journal.finish(checkpoint, ArchiveJournalTest.NOW);
		var finalized = reader().read("task", 0L, null, RunLogCheckpoint.initial(), ArchiveJournalTest.NOW);
		assertThat(finalized.archiveState()).isEqualTo("finalized");
		assertThat(finalized.completion()).isEqualTo("partial");
		assertThat(finalized.reason()).isEqualTo("SOURCE_LOST");
		assertThat(Base64.getDecoder().decode(finalized.bytesBase64()))
			.isEqualTo("captured".getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void exposesSetupNavigationWithoutInventingAnAttempt() {
		append("task", "setup failed".getBytes(StandardCharsets.UTF_8));
		var navigation = reader().navigation(0, checkpoint);
		assertThat(navigation.items()).containsExactly(new ArchiveReader.Segment("0", "setup", null, null));
		assertThat(navigation.nextCursor()).isNull();
	}

	private ArchiveReader reader() {
		return new ArchiveReader(objects, ArchiveJournalTest.RUN, ArchiveJournalTest.VERSION);
	}

	private void append(String stream, byte[] raw) {
		var prior = checkpoint.stream(stream);
		var appended = archive.append(stream, prior,
				new RunLogArchive.Page(stream, prior.sourceOffset(), raw, "{}", false, false, null), false,
				ArchiveJournalTest.NOW, ignored -> false);
		checkpoint = checkpoint.with(stream, journal.append(appended));
	}

}
