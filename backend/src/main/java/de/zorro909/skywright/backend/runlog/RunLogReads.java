package de.zorro909.skywright.backend.runlog;

import de.zorro909.skywright.backend.runsubmission.RunAcceptanceStore;
import de.zorro909.skywright.backend.targetstorage.TargetStorageResolver;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Re-resolves current location and credentials for every bounded read, including
 * reconnects.
 */
@Service
public final class RunLogReads {

	private final RunAcceptanceStore runs;

	private final TargetStorageResolver storages;

	private final RunLogCaptureStore captures;

	private final Clock clock;

	private final Semaphore reads = new Semaphore(8);

	RunLogReads(RunAcceptanceStore runs, TargetStorageResolver storages, RunLogCaptureStore captures, Clock clock) {
		this.runs = runs;
		this.storages = storages;
		this.captures = captures;
		this.clock = clock;
	}

	ArchiveReader.Page read(UUID runId, String stream, Long cursor, Long before) {
		validateStream(stream);
		admit();
		try {
			var run = runs.get(runId);
			var definition = run.definition().value().path("trainingProjectVersion");
			try {
				var target = storages.resolveRunOutputRead(runs.currentStorage(runId),
						definition.path("projectIdentity").asText(), runId.toString());
				try (var objects = new S3ArchiveObjects(target)) {
					return new ArchiveReader(objects, runId, definition.path("manifestArtifactDigest").asText())
						.read(stream, cursor, before, captures.checkpoint(runId), clock.instant());
				}
			}
			catch (ArchiveReader.InvalidCursor invalid) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid archive cursor");
			}
			catch (RuntimeException unavailable) {
				return new ArchiveReader.Page(runId, stream, "unavailable", clock.instant(), "unknown", "unknown", null,
						null, "unknown", "ARCHIVE_UNAVAILABLE", cursor == null ? null : cursor.toString(),
						cursor == null ? null : cursor.toString(), null, "");
			}
		}
		finally {
			reads.release();
		}
	}

	ArchiveReader.Navigation navigation(UUID runId, long cursor) {
		admit();
		try {
			var run = runs.get(runId);
			var definition = run.definition().value().path("trainingProjectVersion");
			try {
				var target = storages.resolveRunOutputRead(runs.currentStorage(runId),
						definition.path("projectIdentity").asText(), runId.toString());
				try (var objects = new S3ArchiveObjects(target)) {
					return new ArchiveReader(objects, runId, definition.path("manifestArtifactDigest").asText())
						.navigation(cursor, captures.checkpoint(runId));
				}
			}
			catch (ArchiveReader.InvalidCursor invalid) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid navigation cursor");
			}
			catch (RuntimeException unavailable) {
				throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Archive navigation unavailable");
			}
		}
		finally {
			reads.release();
		}
	}

	void requireRun(UUID runId) {
		runs.get(runId);
	}

	static void validateStream(String stream) {
		if (!"task".equals(stream) && !"controller".equals(stream))
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid archive stream");
	}

	static Long cursor(String value) {
		if (value == null)
			return null;
		try {
			if (!value.matches("0|[1-9][0-9]{0,18}"))
				throw new NumberFormatException();
			return Long.parseLong(value);
		}
		catch (NumberFormatException invalid) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid archive cursor");
		}
	}

	private void admit() {
		if (!reads.tryAcquire())
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Archive read admission is full");
	}

}
