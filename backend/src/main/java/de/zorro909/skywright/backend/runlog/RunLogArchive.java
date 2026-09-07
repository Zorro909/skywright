package de.zorro909.skywright.backend.runlog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/** Incremental archive accounting and navigation; source bytes are never rewritten. */
public final class RunLogArchive {

	public static final int CHUNK_BYTES = 1024 * 1024;

	public static final int MARKER_BYTES = 4096;

	public static final int FINAL_FETCH_FAILURES = 3;

	private static final byte[] MARKER = "\036SKYWRIGHT_ATTEMPT_V1 ".getBytes(StandardCharsets.US_ASCII);

	private static final JsonMapper JSON = JsonMapper.builder()
		.enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
		.build();

	public record Cursor(long sequence, long bytes, String generation, long sourceOffset, String sourceCursor,
			boolean sourceSealed, String pendingMarker, String attemptId, long setupStart, int finalFailures,
			String partialReason, Instant lastSuccessfulFetch, boolean ready) {
		public static Cursor initial() {
			return new Cursor(0, 0, null, 0, "{}", false, "", null, 0, 0, null, null, false);
		}
	}

	public record Page(String generation, long offset, byte[] bytes, String cursor, boolean endOfFile, boolean sealed,
			String gap, boolean finalSource) {
		public Page(String generation, long offset, byte[] bytes, String cursor, boolean endOfFile, boolean sealed,
				String gap) {
			this(generation, offset, bytes, cursor, endOfFile, sealed, gap, true);
		}

		public Page {
			if (generation == null || generation.isBlank() || generation.length() > 1024 || offset < 0 || bytes == null
					|| bytes.length > CHUNK_BYTES || cursor == null || cursor.length() > 4096 || (sealed && !endOfFile))
				throw new IllegalArgumentException("Invalid bounded log page");
			bytes = bytes.clone();
		}

		@Override
		public byte[] bytes() {
			return bytes.clone();
		}
	}

	public record Boundary(long offset, String kind, String attemptId, Long preparationStart) {
	}

	public record Chunk(int schemaVersion, String stream, long sequence, long offset, int size, String sha256,
			String generation, long sourceOffset, List<Boundary> boundaries, Cursor next) {
		public Chunk {
			boundaries = List.copyOf(boundaries);
		}
	}

	public record Append(byte[] bytes, Chunk chunk, Cursor next) {
		public Append {
			bytes = bytes.clone();
		}

		@Override
		public byte[] bytes() {
			return bytes.clone();
		}
	}

	@FunctionalInterface
	public interface AttemptRecords {

		boolean confirms(String attemptId);

	}

	private final UUID runId;

	private final String projectVersion;

	public RunLogArchive(UUID runId, String projectVersion) {
		this.runId = runId;
		this.projectVersion = projectVersion;
	}

	public Append append(String stream, Cursor prior, Page page, boolean terminal, Instant fetchedAt,
			AttemptRecords attempts) {
		if (!List.of("task", "controller").contains(stream) || prior.ready())
			throw new IllegalArgumentException("Archive stream is closed or invalid");
		boolean changed = !page.generation().equals(prior.generation());
		if (page.offset() != (changed ? 0 : prior.sourceOffset()))
			throw new IllegalArgumentException("Source byte cursor differs");
		String reason = prior.partialReason();
		if (reason == null && changed && prior.generation() != null && !prior.sourceSealed())
			reason = "SOURCE_GENERATION_LOST";
		if (reason == null)
			reason = page.gap();
		byte[] raw = page.bytes();
		String pending = changed ? "" : prior.pendingMarker();
		String attempt = changed ? null : prior.attemptId();
		long setup = changed ? prior.bytes() : prior.setupStart();
		var boundaries = new ArrayList<Boundary>();
		if (stream.equals("task")) {
			if (changed || prior.sourceOffset() == 0)
				boundaries.add(new Boundary(prior.bytes(), "setup", null, null));
			byte[] tail = Base64.getDecoder().decode(pending);
			byte[] combined = new byte[tail.length + raw.length];
			System.arraycopy(tail, 0, combined, 0, tail.length);
			System.arraycopy(raw, 0, combined, tail.length, raw.length);
			long start = prior.bytes() - tail.length;
			pending = "";
			int confirmations = 0;
			for (int i = 0; i < combined.length; i++) {
				if (combined[i] != MARKER[0])
					continue;
				int matched = 0;
				while (matched < MARKER.length && i + matched < combined.length
						&& combined[i + matched] == MARKER[matched])
					matched++;
				if (matched != MARKER.length && i + matched != combined.length)
					continue;
				int end = i + matched;
				while (end < combined.length && end - i <= MARKER_BYTES && combined[end] != 31)
					end++;
				if (end == combined.length && end - i < MARKER_BYTES) {
					pending = Base64.getEncoder().encodeToString(Arrays.copyOfRange(combined, i, end));
					break;
				}
				String id = null;
				if (matched == MARKER.length && end < combined.length && end - i <= MARKER_BYTES
						&& boundaries.size() < 128) {
					try {
						var marker = JSON.readTree(Arrays.copyOfRange(combined, i + MARKER.length, end));
						id = marker.path("attemptId").asText();
						if (marker.size() != 4 || !marker.path("schemaVersion").isIntegralNumber()
								|| marker.path("schemaVersion").asInt() != 1
								|| !marker.path("runId").asText().equals(runId.toString())
								|| !marker.path("projectVersion").asText().equals(projectVersion)
								|| !UUID.fromString(id).toString().equals(id) || attempt != null)
							id = null;
					}
					catch (RuntimeException invalid) {
						id = null;
					}
				}
				if (id != null && (++confirmations > 4 || !attempts.confirms(id)))
					id = null;
				if (id == null) {
					if (reason == null)
						reason = "UNVERIFIABLE_BOUNDARY";
				}
				else {
					boundaries.add(new Boundary(start + end + 1, "attempt", id, setup));
					attempt = id;
					setup = -1;
				}
				i = end;
			}
		}
		boolean ready = terminal && page.endOfFile() && page.sealed() && page.finalSource();
		if (page.sealed() && !pending.isEmpty() && reason == null)
			reason = "UNVERIFIABLE_BOUNDARY";
		var next = new Cursor(prior.sequence() + (raw.length == 0 ? 0 : 1), Math.addExact(prior.bytes(), raw.length),
				page.generation(), Math.addExact(page.offset(), raw.length), page.cursor(), page.sealed(), pending,
				attempt, setup, 0, reason, fetchedAt, ready);
		var chunk = raw.length == 0 ? null : new Chunk(1, stream, prior.sequence(), prior.bytes(), raw.length,
				digest(raw), page.generation(), page.offset(), boundaries, next);
		return new Append(raw, chunk, next);
	}

	/** Only failed terminal fetches consume the durable retry budget. */
	public Cursor unavailable(Cursor prior, boolean terminal, String reason) {
		if (prior.ready())
			return prior;
		int failures = terminal ? Math.min(FINAL_FETCH_FAILURES, prior.finalFailures() + 1) : 0;
		return new Cursor(prior.sequence(), prior.bytes(), prior.generation(), prior.sourceOffset(),
				prior.sourceCursor(), prior.sourceSealed(), prior.pendingMarker(), prior.attemptId(),
				prior.setupStart(), failures,
				prior.partialReason() == null && failures == FINAL_FETCH_FAILURES ? reason : prior.partialReason(),
				prior.lastSuccessfulFetch(), failures == FINAL_FETCH_FAILURES);
	}

	public static String digest(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (java.security.NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	static Cursor partial(Cursor prior, String reason) {
		return new Cursor(prior.sequence(), prior.bytes(), prior.generation(), prior.sourceOffset(),
				prior.sourceCursor(), prior.sourceSealed(), prior.pendingMarker(), prior.attemptId(),
				prior.setupStart(), prior.finalFailures(),
				prior.partialReason() == null ? reason : prior.partialReason(), prior.lastSuccessfulFetch(), true);
	}

}
