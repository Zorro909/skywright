package de.zorro909.skywright.backend.runstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.nio.file.Files;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RunStoreAccessTest {

	@TempDir
	Path temporary;

	@Test
	void listsValidatesAndPresignsExactImmutableOutputs() {
		MemoryObjectStore objects = new MemoryObjectStore();
		RunStoreProtocol protocol = new RunStoreProtocol("project", "run");
		String key = protocol.artifactKey("123e4567-e89b-12d3-a456-426614174000", 7, "plots/loss.png");
		objects.put(key, "artifact".getBytes(StandardCharsets.UTF_8), "application/octet-stream", "artifact");
		byte[] checkpoint = "large-checkpoint".getBytes(StandardCharsets.UTF_8);
		objects.put(protocol.checkpointKey(7, sha256(checkpoint)), checkpoint, "application/octet-stream",
				"checkpoint");
		RunStoreAccess access = new RunStoreAccess(protocol, objects);

		assertThat(access.listOutputs(RunStoreOutputKind.ARTIFACT, 10, null).outputs())
			.containsExactly(new RunStoreOutput(RunStoreOutputKind.ARTIFACT, 7, "plots/loss.png", key, 8,
					"application/octet-stream", sha256("artifact".getBytes(StandardCharsets.UTF_8))));
		assertThat(access.presignDownload(key, 900).url())
			.isEqualTo(URI.create("https://download.invalid/exact?expires=900"));
		assertThat(objects.listedPrefixes).containsExactly(protocol.runPrefix() + "artifacts/");
	}

	@Test
	void resolvesLocationIndependentCheckpointReferencesWithoutDecodingState() {
		MemoryObjectStore objects = new MemoryObjectStore();
		RunStoreProtocol protocol = new RunStoreProtocol("project", "run");
		byte[] checkpoint = "safetensors".getBytes(StandardCharsets.UTF_8);
		String digest = sha256(checkpoint);
		String key = protocol.checkpointKey(42, digest);
		objects.put(key, checkpoint, "application/octet-stream", "checkpoint");

		RunStoreObjectMetadata object = new RunStoreAccess(protocol, objects)
			.resolveCheckpoint("skywright-checkpoint:v1:42:sha256:" + digest);

		assertThat(object.key()).isEqualTo(key);
		assertThat(object.size()).isEqualTo(checkpoint.length);
		assertThat(objects.gets).isZero();
	}

	@Test
	void readsAndValidatesTheCurrentProgressProjection() {
		MemoryObjectStore objects = new MemoryObjectStore();
		RunStoreProtocol protocol = new RunStoreProtocol("project", "run");
		byte[] progress = """
				{"currentStep":3,"latestDurableCheckpoint":null,"latestDurableStep":null,"runId":"run","schemaVersion":1,"writtenAt":"2026-08-22T12:34:56Z"}"""
			.getBytes(StandardCharsets.UTF_8);
		objects.put(protocol.progressKey(), progress, "application/json", "progress-record");

		ProgressRecord record = new RunStoreAccess(protocol, objects).readProgress();

		assertThat(record.runId()).isEqualTo("run");
		assertThat(record.currentStep()).isEqualTo(3);
		assertThat(record.latestDurableStep()).isNull();
		assertThat(record.targetStep()).isNull();
	}

	@Test
	void signingReportsExpectedIntegrityAndConsumptionRejectsCorruption() throws Exception {
		MemoryObjectStore objects = new MemoryObjectStore();
		RunStoreProtocol protocol = new RunStoreProtocol("project", "run");
		String key = protocol.sampleKey("123e4567-e89b-12d3-a456-426614174000", 1, "preview.png");
		objects.put(key, "original".getBytes(StandardCharsets.UTF_8), "image/png", "sample");
		objects.corrupt(key, "tampered".getBytes(StandardCharsets.UTF_8));
		RunStoreAccess access = new RunStoreAccess(protocol, objects);
		assertThat(access.listOutputs(RunStoreOutputKind.SAMPLE, 10, null).outputs()).hasSize(1);
		RunStoreDownloadLink link = access.presignDownload(key, 900);
		assertThat(link.verification()).isEqualTo(RunStoreDownloadLink.Verification.NOT_RECORDED);
		assertThat(link.digest()).isEqualTo(sha256("original".getBytes(StandardCharsets.UTF_8)));
		assertThat(objects.gets).isZero();
		assertThatThrownBy(() -> access.stageDownload(key, temporary, 1024))
			.isInstanceOf(RunStoreIntegrityException.class)
			.hasMessageContaining("RUN_STORE_DIGEST_MISMATCH");
		try (var files = Files.list(temporary)) {
			assertThat(files).isEmpty();
		}
		assertThat(objects.closed).isEqualTo(1);
	}

	@Test
	void paginatesMetadataAndBoundsVerifiedStaging() throws Exception {
		MemoryObjectStore objects = new MemoryObjectStore();
		RunStoreProtocol protocol = new RunStoreProtocol("project", "run");
		for (int step = 0; step < 100; step++) {
			objects.put(protocol.artifactKey("123e4567-e89b-12d3-a456-426614174000", step, "weights"),
					new byte[1024 * 1024], "application/octet-stream", "artifact");
		}
		RunStoreAccess access = new RunStoreAccess(protocol, objects);
		String continuation = null;
		int count = 0;
		do {
			RunStoreOutputPage page = access.listOutputs(RunStoreOutputKind.ARTIFACT, 7, continuation);
			assertThat(page.outputs().size()).isLessThanOrEqualTo(7);
			count += page.outputs().size();
			continuation = page.continuation();
		}
		while (continuation != null);
		assertThat(count).isEqualTo(100);
		assertThat(objects.gets).isZero();
		String key = objects.objects.keySet().iterator().next();
		assertThatThrownBy(() -> access.stageDownload(key, temporary, 100))
			.hasMessageContaining("RUN_STORE_STAGING_BUDGET");
		assertThat(objects.closed).isEqualTo(1);
		Path staged;
		try (VerifiedRunStoreObject object = access.stageDownload(key, temporary, 1024 * 1024)) {
			staged = object.path();
			assertThat(Files.size(staged)).isEqualTo(1024 * 1024);
		}
		assertThat(staged).doesNotExist();
	}

	@Test
	void rejectsMalformedIdentityAndMetadataBeforeSigning() {
		MemoryObjectStore objects = new MemoryObjectStore();
		RunStoreProtocol protocol = new RunStoreProtocol("project", "run");
		RunStoreAccess access = new RunStoreAccess(protocol, objects);
		String key = protocol.artifactKey("123e4567-e89b-12d3-a456-426614174000", 0, "weights");
		objects.put(key, new byte[0], "application/octet-stream", "sample");
		assertThatThrownBy(() -> access.presignDownload(key, 60)).hasMessageContaining("RUN_STORE_METADATA_MISMATCH");
		for (String invalid : List.of("other/run/v1/artifacts/x", key + "/nested", key + "%FF", key + "%41")) {
			assertThatThrownBy(() -> access.presignDownload(invalid, 60)).hasMessageContaining("RUN_STORE_INVALID_KEY");
		}
		assertThat(objects.gets).isZero();
	}

	@Test
	void attemptReadsVerifyIdentityAndIntegrityBeforeCorrelation() {
		var objects = new MemoryObjectStore();
		var protocol = new RunStoreProtocol("project", "run");
		String attempt = "123e4567-e89b-12d3-a456-426614174000";
		String key = protocol.attemptRecordKey(attempt);
		byte[] body = ("{\"schemaVersion\":1,\"runId\":\"run\",\"attemptId\":\"" + attempt
				+ "\",\"projectVersion\":\"version\"}")
			.getBytes(StandardCharsets.UTF_8);
		objects.put(key, body, "application/json", "execution-attempt-record");
		var access = new RunStoreAccess(protocol, objects);
		assertThat(access.listAttempts(1, null).attempts())
			.containsExactly(new ExecutionAttemptReference("run", attempt, "version"));
		objects.corrupt(key, "corrupt".getBytes(StandardCharsets.UTF_8));
		assertThatThrownBy(() -> access.readAttempt(attempt)).isInstanceOf(RunStoreIntegrityException.class);
		objects.put(key, new String(body, StandardCharsets.UTF_8).replace("\"run\"", "\"other\"")
			.getBytes(StandardCharsets.UTF_8), "application/json", "execution-attempt-record");
		assertThatThrownBy(() -> access.readAttempt(attempt)).hasMessageContaining("ATTEMPT_IDENTITY_INVALID");
		objects.put(key, new byte[65537], "application/json", "execution-attempt-record");
		assertThatThrownBy(() -> access.readAttempt(attempt)).hasMessageContaining("ATTEMPT_SIZE_OR_KEY_INVALID");
		assertThatThrownBy(() -> access.listAttempts(1001, null)).isInstanceOf(IllegalArgumentException.class);
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (Exception failure) {
			throw new IllegalStateException(failure);
		}
	}

	private static final class MemoryObjectStore implements RunStoreObjectStore {

		private final Map<String, RunStoreObject> objects = new LinkedHashMap<>();

		private final List<String> listedPrefixes = new ArrayList<>();

		private int gets;

		private int closed;

		void put(String key, byte[] bytes, String contentType, String kind) {
			this.objects.put(key,
					new RunStoreObject(key, bytes, contentType,
							Map.of("skywright-sha256", sha256(bytes), "skywright-size", Integer.toString(bytes.length),
									"skywright-kind", kind, "skywright-schema", "v1")));
		}

		void corrupt(String key, byte[] bytes) {
			RunStoreObject existing = this.objects.get(key);
			this.objects.put(key, new RunStoreObject(key, bytes, existing.contentType(), existing.metadata()));
		}

		@Override
		public RunStoreObjectPage list(String prefix, int limit, String continuation) {
			this.listedPrefixes.add(prefix);
			var matching = this.objects.values().stream().filter(item -> item.key().startsWith(prefix)).toList();
			int offset = continuation == null ? 0 : Integer.parseInt(continuation);
			int end = Math.min(matching.size(), offset + limit);
			return new RunStoreObjectPage(matching.subList(offset, end)
				.stream()
				.map(item -> new RunStoreObjectPage.Entry(item.key(), item.bytes().length))
				.toList(), end < matching.size() ? Integer.toString(end) : null);
		}

		@Override
		public RunStoreObjectMetadata head(String key) {
			RunStoreObject object = this.objects.get(key);
			return object == null ? null
					: new RunStoreObjectMetadata(key, object.bytes().length, object.contentType(), object.metadata());
		}

		@Override
		public RunStoreContent open(String key) {
			this.gets++;
			RunStoreObject object = this.objects.get(key);
			return object == null ? null : new RunStoreContent(head(key), new ByteArrayInputStream(object.bytes()) {
				@Override
				public void close() {
					closed++;
				}
			});
		}

		@Override
		public URI presignGet(String key, int expiresInSeconds, String contentType, String filename) {

			return URI.create("https://download.invalid/exact?expires=" + expiresInSeconds);
		}

	}

}
