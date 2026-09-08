package de.zorro909.skywright.backend.runlog;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** Verifies bounded archive windows using absolute byte positions, never source logs. */
final class ArchiveReader {

	static final int PAGE_BYTES = 64 * 1024;

	static final int PAGE_CHUNKS = 4;

	static final class InvalidCursor extends IllegalArgumentException {

		InvalidCursor() {
			super("Invalid archive cursor");
		}

	}

	private final ArchiveObjects objects;

	private final ArchiveJournal journal;

	private final UUID runId;

	private final String manifestDigest;

	ArchiveReader(ArchiveObjects objects, UUID runId, String version, String manifestDigest) {
		this.objects = objects;
		this.journal = new ArchiveJournal(objects, runId, version);
		this.runId = runId;
		this.manifestDigest = manifestDigest;
	}

	record Page(UUID runId, String stream, String availability, Instant observedAt, String archiveState,
			String sourceAvailability, String sourceReason, Instant lastSuccessfulFetch, String completion,
			String reason, String fromCursor, String nextCursor, String endCursor, String bytesBase64) {
	}

	record Segment(String cursor, String kind, String attemptId, String preparationCursor) {
	}

	record Navigation(List<Segment> items, String nextCursor) {
	}

	Page read(String stream, Long cursor, Long before, RunLogCheckpoint staging, Instant now) {
		var manifest = journal.manifest(manifestDigest);
		var checkpoint = manifest == null ? staging : manifest.checkpoint();
		var head = checkpoint.stream(stream);
		validateHead(head);
		long end = before == null ? head.bytes() : before;
		if (end < 0 || end > head.bytes() || cursor != null && before != null)
			throw new InvalidCursor();
		long from = cursor == null ? Math.max(0, end - PAGE_BYTES) : cursor;
		if (cursor == null && end > 0) {
			long last = locate(stream, end - 1, head.sequence());
			from = Math.max(from, requiredChunk(stream, Math.max(0, last - PAGE_CHUNKS + 1)).offset());
		}
		if (from < 0 || from > end)
			throw new InvalidCursor();
		long limit = from + Math.min(PAGE_BYTES, end - from);
		var output = new ByteArrayOutputStream(PAGE_BYTES);
		long position = from;
		if (position < limit) {
			long sequence = locate(stream, position, head.sequence());
			for (int count = 0; count < PAGE_CHUNKS && position < limit; count++, sequence++) {
				var chunk = requiredChunk(stream, sequence);
				if (chunk.offset() > position || chunk.offset() + chunk.size() <= position)
					throw new IllegalStateException("ARCHIVE_RANGE_INVALID");
				byte[] raw = objects.read(ArchiveJournal.rawKey(chunk), RunLogArchive.CHUNK_BYTES);
				if (raw == null || raw.length != chunk.size() || !RunLogArchive.digest(raw).equals(chunk.sha256()))
					throw new IllegalStateException("ARCHIVE_CHUNK_INVALID");
				int start = Math.toIntExact(position - chunk.offset());
				int length = (int) Math.min(raw.length - start, limit - position);
				output.write(raw, start, length);
				position += length;
			}
		}
		String failure = checkpoint.sourceFailure(stream);
		return new Page(runId, stream, "available", now, manifest == null ? "staging" : "finalized",
				failure != null ? "unavailable" : head.lastSuccessfulFetch() == null ? "not-observed" : "available",
				failure, head.lastSuccessfulFetch(),
				manifest == null ? "pending" : head.partialReason() == null ? "complete" : "partial",
				head.partialReason(), Long.toString(from), Long.toString(position), Long.toString(head.bytes()),
				Base64.getEncoder().encodeToString(output.toByteArray()));
	}

	/**
	 * Navigation is paged separately from byte cursors; one bounded immutable index per
	 * page.
	 */
	Navigation navigation(long sequence, RunLogCheckpoint staging) {
		var manifest = journal.manifest(manifestDigest);
		var head = (manifest == null ? staging : manifest.checkpoint()).task();
		validateHead(head);
		if (sequence < 0 || sequence > head.sequence())
			throw new InvalidCursor();
		if (sequence == head.sequence())
			return new Navigation(List.of(), null);
		var chunk = requiredChunk("task", sequence);
		var items = chunk.boundaries().stream().map(boundary -> {
			if (boundary.offset() < 0 || boundary.offset() > head.bytes()
					|| !List.of("setup", "attempt").contains(boundary.kind())
					|| boundary.kind().equals("attempt") && (boundary.attemptId() == null
							|| !UUID.fromString(boundary.attemptId()).toString().equals(boundary.attemptId())))
				throw new IllegalStateException("ARCHIVE_NAVIGATION_INVALID");
			return new Segment(Long.toString(boundary.offset()), boundary.kind(), boundary.attemptId(),
					boundary.preparationStart() == null ? null : Long.toString(boundary.preparationStart()));
		}).toList();
		return new Navigation(items, sequence + 1 < head.sequence() ? Long.toString(sequence + 1) : null);
	}

	private long locate(String stream, long position, long count) {
		long low = 0;
		long high = count - 1;
		// A long cursor takes at most 63 index reads, independent of archive length.
		while (low <= high) {
			long middle = low + (high - low) / 2;
			var chunk = requiredChunk(stream, middle);
			if (position < chunk.offset())
				high = middle - 1;
			else if (position >= chunk.offset() + chunk.size())
				low = middle + 1;
			else
				return middle;
		}
		throw new IllegalStateException("ARCHIVE_CURSOR_UNRESOLVED");
	}

	private RunLogArchive.Chunk requiredChunk(String stream, long sequence) {
		var chunk = journal.chunk(stream, sequence);
		if (chunk == null || chunk.offset() > Long.MAX_VALUE - chunk.size() || chunk.boundaries().size() > 128)
			throw new IllegalStateException("ARCHIVE_INDEX_INVALID");
		return chunk;
	}

	private static void validateHead(RunLogArchive.Cursor head) {
		if (head.bytes() < 0 || head.sequence() < 0 || head.sequence() > head.bytes()
				|| (head.bytes() == 0) != (head.sequence() == 0))
			throw new IllegalStateException("ARCHIVE_HEAD_INVALID");
	}

}
