package de.zorro909.skywright.backend.runlog;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * Raw chunks precede immutable indexes; exact next-index lookup recovers lost
 * acknowledgements.
 */
final class ArchiveJournal {

	static final String ROOT = "skypilot/logs/";
	static final String MANIFEST = ROOT + "manifest.json";

	private static final JsonMapper JSON = JsonMapper.builder()
		.enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
		.build();

	private final ArchiveObjects objects;

	private final UUID runId;

	private final String version;

	ArchiveJournal(ArchiveObjects objects, UUID runId, String version) {
		this.objects = objects;
		this.runId = runId;
		this.version = version;
	}

	record Manifest(int schemaVersion, UUID runId, String projectVersion, Instant publishedAt,
			RunLogCheckpoint checkpoint, Stream task, Stream controller) {
	}

	record Stream(String status, String reason, long bytes, long chunks, Instant lastSuccessfulFetch) {
	}

	Manifest manifest() {
		byte[] body = objects.read(MANIFEST, 65536);
		return body == null ? null : decodeManifest(body);
	}

	private Manifest decodeManifest(byte[] body) {
		var manifest = JSON.readValue(body, Manifest.class);
		if (manifest.schemaVersion() != 1 || !runId.equals(manifest.runId())
				|| !version.equals(manifest.projectVersion()) || !manifest.checkpoint().ready()
				|| !manifest.checkpoint().markersVerified() || manifest.publishedAt() == null)
			throw new IllegalStateException("ARCHIVE_MANIFEST_INVALID");
		return manifest;
	}

	RunLogArchive.Chunk chunk(String stream, long sequence) {
		byte[] record = objects.read(indexKey(stream, sequence), 65536);
		if (record == null)
			return null;
		var chunk = JSON.readValue(record, RunLogArchive.Chunk.class);
		if (chunk.schemaVersion() != 1 || !chunk.stream().equals(stream) || chunk.sequence() != sequence
				|| chunk.size() < 1 || chunk.size() > RunLogArchive.CHUNK_BYTES || chunk.offset() < 0
				|| chunk.next().sequence() != sequence + 1 || chunk.next().bytes() != chunk.offset() + chunk.size()
				|| !chunk.sha256().matches("[0-9a-f]{64}"))
			throw new IllegalStateException("ARCHIVE_INDEX_INVALID");
		return chunk;
	}

	RunLogArchive.Cursor recover(String stream, RunLogArchive.Cursor prior) {
		var next = chunk(stream, prior.sequence());
		if (next == null)
			return prior;
		if (next.offset() != prior.bytes())
			throw new IllegalStateException("ARCHIVE_CURSOR_INVALID");
		byte[] raw = objects.read(rawKey(next), RunLogArchive.CHUNK_BYTES);
		if (raw == null || raw.length != next.size() || !RunLogArchive.digest(raw).equals(next.sha256()))
			throw new IllegalStateException("ARCHIVE_CHUNK_INVALID");
		publishNavigation(next);
		return next.next();
	}

	RunLogArchive.Cursor append(RunLogArchive.Append append) {
		if (append.chunk() == null)
			return append.next();
		var chunk = append.chunk();
		byte[] raw = objects.publish(rawKey(chunk), append.bytes(), "raw");
		if (!Arrays.equals(raw, append.bytes()))
			throw new IllegalStateException("ARCHIVE_CHUNK_CONFLICT");
		byte[] metadata = JSON.writeValueAsBytes(chunk);
		if (metadata.length > 65536)
			throw new IllegalStateException("ARCHIVE_INDEX_TOO_LARGE");
		byte[] written = objects.publish(indexKey(chunk.stream(), chunk.sequence()), metadata, "index");
		var winner = JSON.readValue(written, RunLogArchive.Chunk.class);
		if (!winner.equals(chunk))
			throw new IllegalStateException("ARCHIVE_INDEX_CONFLICT");
		publishNavigation(winner);
		return winner.next();
	}

	private void publishNavigation(RunLogArchive.Chunk chunk) {
		for (var boundary : chunk.boundaries()) {
			if (!boundary.kind().equals("attempt"))
				continue;
			byte[] body = JSON.writeValueAsBytes(boundary);
			byte[] written = objects.publish(ROOT + "navigation/" + boundary.attemptId() + ".json", body, "navigation");
			if (!Arrays.equals(body, written))
				throw new IllegalStateException("ARCHIVE_NAVIGATION_CONFLICT");
		}
	}

	boolean confirms(String attemptId) {
		if (objects.read(ROOT + "navigation/" + UUID.fromString(attemptId) + ".json", 4096) != null)
			return false;
		byte[] record = objects.read("attempts/" + UUID.fromString(attemptId) + "/record.json", 65536);
		if (record == null)
			return false;
		var value = JSON.readTree(record);
		return value.path("schemaVersion").asInt() == 1 && value.path("runId").asText().equals(runId.toString())
				&& value.path("projectVersion").asText().equals(version)
				&& value.path("attemptId").asText().equals(attemptId);
	}

	RunLogCheckpoint verifyMarkers(RunLogCheckpoint checkpoint) {
		if (checkpoint.markersVerified())
			return checkpoint;
		var keys = objects.keysAfter("attempts/", checkpoint.markerAfter(), 4);
		for (String key : keys) {
			if (!key.endsWith("/record.json"))
				continue;
			String id = key.substring("attempts/".length(), key.length() - "/record.json".length());
			if (objects.read(ROOT + "navigation/" + UUID.fromString(id) + ".json", 4096) == null) {
				return checkpoint.with("task", RunLogArchive.partial(checkpoint.task(), "UNVERIFIABLE_BOUNDARY"))
					.markers(key, true);
			}
		}
		return checkpoint.markers(keys.isEmpty() ? checkpoint.markerAfter() : keys.getLast(), keys.size() < 4);
	}

	Manifest finish(RunLogCheckpoint checkpoint, Instant now) {
		if (!checkpoint.ready() || !checkpoint.markersVerified())
			throw new IllegalStateException("Archive is not finalized");
		var manifest = new Manifest(1, runId, version, now, checkpoint, summary(checkpoint.task()),
				summary(checkpoint.controller()));
		return decodeManifest(objects.publish(MANIFEST, JSON.writeValueAsBytes(manifest), "manifest"));
	}

	static byte[] encode(Manifest manifest) {
		return JSON.writeValueAsBytes(manifest);
	}

	static String indexKey(String stream, long sequence) {
		return ROOT + stream + "/index/%019d.json".formatted(sequence);
	}

	static String rawKey(RunLogArchive.Chunk chunk) {
		return ROOT + chunk.stream() + "/chunks/%019d-".formatted(chunk.offset()) + chunk.sha256();
	}

	private static Stream summary(RunLogArchive.Cursor cursor) {
		return new Stream(cursor.partialReason() == null ? "complete" : "partial", cursor.partialReason(),
				cursor.bytes(), cursor.sequence(), cursor.lastSuccessfulFetch());
	}

}
