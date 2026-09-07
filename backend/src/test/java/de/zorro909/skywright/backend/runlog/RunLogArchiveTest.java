package de.zorro909.skywright.backend.runlog;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RunLogArchiveTest {

	private static final UUID RUN = UUID.fromString("38c76a5b-7cba-400e-9595-7657b194ea83");

	private static final String ATTEMPT = "739e441a-d412-42ed-8a79-5ee9588a6a97";

	private static final Instant NOW = Instant.parse("2026-09-07T10:00:00Z");

	private final RunLogArchive archive = new RunLogArchive(RUN, "project-v1");

	private static RunLogArchive.Page page(String generation, long offset, byte[] bytes, boolean sealed) {
		return new RunLogArchive.Page(generation, offset, bytes, "{}", true, sealed, null);
	}

	@Test
	void preservesEveryByteAndKeepsControllerProvenanceSeparate() {
		byte[] bytes = { 27, '[', 'm', 13, 10, (byte) 255, 0, 'x' };
		var result = archive.append("controller", RunLogArchive.Cursor.initial(), page("controller-1", 0, bytes, true),
				true, NOW, ignored -> {
					throw new AssertionError("Controller bytes must not be parsed as markers");
				});
		assertThat(result.bytes()).isEqualTo(bytes);
		assertThat(result.chunk().boundaries()).isEmpty();
		assertThat(result.chunk().stream()).isEqualTo("controller");
		assertThat(result.next().bytes()).isEqualTo(bytes.length);
		assertThat(result.next().ready()).isTrue();
		assertThat(result.next().partialReason()).isNull();
	}

	@Test
	void validatesSplitMarkerAfterRestartWithoutRewritingChunks() {
		byte[] marker = marker();
		int cut = marker.length / 2;
		byte[] first = Arrays.copyOf(marker, cut);
		byte[] second = Arrays.copyOfRange(marker, cut, marker.length);
		var initial = archive.append("task", RunLogArchive.Cursor.initial(), page("task-1", 0, first, false), false,
				NOW, ATTEMPT::equals);
		var json = JsonMapper.builder().build();
		var restored = json.readValue(json.writeValueAsString(initial.next()), RunLogArchive.Cursor.class);
		var result = archive.append("task", restored, page("task-1", cut, second, true), true, NOW, ATTEMPT::equals);
		assertThat(initial.bytes()).isEqualTo(first);
		assertThat(result.bytes()).isEqualTo(second);
		assertThat(result.chunk().offset()).isEqualTo(cut);
		assertThat(result.chunk().sequence()).isEqualTo(1);
		assertThat(result.chunk().boundaries())
			.containsExactly(new RunLogArchive.Boundary(marker.length - 1, "attempt", ATTEMPT, 0L));
		assertThat(result.next().pendingMarker()).isEmpty();
		assertThat(result.next().partialReason()).isNull();
	}

	@Test
	void forgedMarkerRemainsRawButCannotCreateAnAttemptIndex() {
		var result = archive.append("task", RunLogArchive.Cursor.initial(), page("task-1", 0, marker(), true), true,
				NOW, ignored -> false);
		assertThat(result.bytes()).isEqualTo(marker());
		assertThat(result.chunk().boundaries()).extracting(RunLogArchive.Boundary::kind).containsExactly("setup");
		assertThat(result.next().partialReason()).isEqualTo("UNVERIFIABLE_BOUNDARY");
	}

	@Test
	void terminalComputeDoesNotSealAnOpenSource() {
		var open = archive
			.append("task", RunLogArchive.Cursor.initial(), page("task-1", 0, new byte[] { 1 }, false), true, NOW,
					ignored -> true)
			.next();
		assertThat(open.ready()).isFalse();
		var once = archive.unavailable(open, true, "WRITER_UNCONFIRMED");
		var twice = archive.unavailable(once, true, "WRITER_UNCONFIRMED");
		assertThat(twice.ready()).isFalse();
		var exhausted = archive.unavailable(twice, true, "WRITER_UNCONFIRMED");
		assertThat(exhausted.ready()).isTrue();
		assertThat(exhausted.partialReason()).isEqualTo("WRITER_UNCONFIRMED");
		assertThat(exhausted.bytes()).isEqualTo(1);
	}

	@Test
	void recoveryKeepsEarlierLossEvenWhenTheLastGenerationIsComplete() {
		var first = archive
			.append("task", RunLogArchive.Cursor.initial(), page("pod-a", 0, new byte[] { 1 }, false), false, NOW,
					ignored -> false)
			.next();
		var later = archive.append("task", first, page("pod-b", 0, new byte[] { 2 }, true), true, NOW,
				ignored -> false);
		assertThat(later.next().ready()).isTrue();
		assertThat(later.next().partialReason()).isEqualTo("SOURCE_GENERATION_LOST");
		assertThat(later.chunk().offset()).isEqualTo(1);
		assertThat(later.chunk().boundaries()).containsExactly(new RunLogArchive.Boundary(1, "setup", null, null));
	}

	@Test
	void longUnterminatedMarkerCannotGrowThePersistentBuffer() {
		byte[] body = new byte[RunLogArchive.CHUNK_BYTES];
		Arrays.fill(body, (byte) 'x');
		byte[] prefix = "\036SKYWRIGHT_ATTEMPT_V1 ".getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(prefix, 0, body, 0, prefix.length);
		var result = archive.append("task", RunLogArchive.Cursor.initial(), page("task-1", 0, body, false), false, NOW,
				ignored -> false);
		assertThat(result.next().pendingMarker()).hasSizeLessThanOrEqualTo(RunLogArchive.MARKER_BYTES * 2);
		assertThat(result.next().partialReason()).isEqualTo("UNVERIFIABLE_BOUNDARY");
		assertThat(result.bytes()).isEqualTo(body);
	}

	private static byte[] marker() {
		return ("\036SKYWRIGHT_ATTEMPT_V1 {\"schemaVersion\":1,\"runId\":\"" + RUN + "\",\"attemptId\":\"" + ATTEMPT
				+ "\",\"projectVersion\":\"project-v1\"}\037\n")
			.getBytes(StandardCharsets.US_ASCII);
	}

}
