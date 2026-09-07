package de.zorro909.skywright.backend.runlifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import de.zorro909.skywright.backend.runstore.*;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RunProgressReadsTest {

	private final RunStoreProtocol protocol = new RunStoreProtocol("project", "run");

	private final Objects objects = new Objects();

	@Test
	void distinguishesAbsentAndUnavailableWithoutInventingProgress() {
		assertThat(RunProgressReads.read(protocol, objects).availability()).isEqualTo("absent");
		objects.unavailable = true;
		var unavailable = RunProgressReads.read(protocol, objects);
		assertThat(unavailable.availability()).isEqualTo("unavailable");
		assertThat(unavailable.record()).isNull();
	}

	@Test
	void readsIndependentCommittedAndDurableStepsWithoutATarget() {
		String reference = "skywright-checkpoint:v1:10:sha256:" + "a".repeat(64);
		objects.body = ("""
				{"schemaVersion":1,"runId":"run","currentStep":12,"latestDurableStep":10,
				"latestDurableCheckpoint":"%s","writtenAt":"2026-09-07T10:00:00Z"}
				""").formatted(reference).getBytes(StandardCharsets.UTF_8);
		var result = RunProgressReads.read(protocol, objects);
		assertThat(result.availability()).isEqualTo("available");
		assertThat(result.record().currentStep()).isEqualTo(12);
		assertThat(result.record().latestDurableStep()).isEqualTo(10);
		assertThat(result.record().targetStep()).isNull();
	}

	@Test
	void rejectsOversizedAndWrongRunRecords() {
		objects.body = new byte[16 * 1024 + 1];
		assertThat(RunProgressReads.read(protocol, objects).availability()).isEqualTo("invalid");
		objects.body = """
				{"schemaVersion":1,"runId":"another-run","currentStep":0,"latestDurableStep":null,
				"latestDurableCheckpoint":null,"writtenAt":"2026-09-07T10:00:00Z"}
				""".getBytes(StandardCharsets.UTF_8);
		assertThat(RunProgressReads.read(protocol, objects).availability()).isEqualTo("invalid");
	}

	private class Objects implements RunStoreObjectStore {

		byte[] body;

		boolean unavailable;

		@Override
		public RunStoreContent open(String key) {
			assertThat(key).isEqualTo(protocol.progressKey());
			if (unavailable)
				throw new IllegalStateException("private endpoint");
			if (body == null)
				return null;
			try {
				var metadata = Map.of("skywright-size", Integer.toString(body.length), "skywright-sha256",
						HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)), "skywright-schema",
						"v1", "skywright-kind", "progress-record");
				return new RunStoreContent(new RunStoreObjectMetadata(key, body.length, "application/json", metadata),
						new ByteArrayInputStream(body));
			}
			catch (java.security.NoSuchAlgorithmException impossible) {
				throw new AssertionError(impossible);
			}
		}

		@Override
		public RunStoreObjectPage list(String prefix, int limit, String continuation) {
			throw new AssertionError("Progress never lists history");
		}

		@Override
		public RunStoreObjectMetadata head(String key) {
			throw new AssertionError("Progress uses one bounded object read");
		}

		@Override
		public URI presignGet(String key, int expires, String contentType, String filename) {
			throw new AssertionError("Progress is not a download");
		}

	}

}
