package de.zorro909.skywright.backend.runstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RunStoreAccessTest {

	@Test
	void listsValidatesAndPresignsExactImmutableOutputs() {
		MemoryObjectStore objects = new MemoryObjectStore();
		RunStoreProtocol protocol = new RunStoreProtocol("project", "run");
		String key = protocol.artifactKey("123e4567-e89b-12d3-a456-426614174000", 7, "plots/loss.png");
		objects.put(key, "artifact".getBytes(StandardCharsets.UTF_8), "application/octet-stream", "artifact");
		RunStoreAccess access = new RunStoreAccess(protocol, objects);

		assertThat(access.listOutputs())
			.containsExactly(new RunStoreOutput(RunStoreOutputKind.ARTIFACT, 7, "plots/loss.png", key, 8,
					"application/octet-stream", sha256("artifact".getBytes(StandardCharsets.UTF_8))));
		assertThat(access.presignDownload(key, 900))
			.isEqualTo(URI.create("https://download.invalid/exact?expires=900"));
	}

	@Test
	void resolvesLocationIndependentCheckpointReferencesWithoutDecodingState() {
		MemoryObjectStore objects = new MemoryObjectStore();
		RunStoreProtocol protocol = new RunStoreProtocol("project", "run");
		byte[] checkpoint = "safetensors".getBytes(StandardCharsets.UTF_8);
		String digest = sha256(checkpoint);
		String key = protocol.checkpointKey(42, digest);
		objects.put(key, checkpoint, "application/octet-stream", "checkpoint");

		RunStoreObject object = new RunStoreAccess(protocol, objects)
			.resolveCheckpoint("skywright-checkpoint:v1:42:sha256:" + digest);

		assertThat(object.key()).isEqualTo(key);
		assertThat(object.bytes()).containsExactly(checkpoint);
	}

	@Test
	void detectsDigestCorruptionBeforeServingOrPresigning() {
		MemoryObjectStore objects = new MemoryObjectStore();
		RunStoreProtocol protocol = new RunStoreProtocol("project", "run");
		String key = protocol.sampleKey("123e4567-e89b-12d3-a456-426614174000", 1, "preview.png");
		objects.put(key, "original".getBytes(StandardCharsets.UTF_8), "image/png", "sample");
		objects.corrupt(key, "changed".getBytes(StandardCharsets.UTF_8));
		RunStoreAccess access = new RunStoreAccess(protocol, objects);

		assertThatThrownBy(access::listOutputs).isInstanceOf(RunStoreIntegrityException.class)
			.hasMessageContaining("RUN_STORE_DIGEST_MISMATCH");
		assertThatThrownBy(() -> access.presignDownload(key, 900)).isInstanceOf(RunStoreIntegrityException.class);
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
		public List<RunStoreObject> list(String prefix) {
			return this.objects.values().stream().filter(item -> item.key().startsWith(prefix)).toList();
		}

		@Override
		public RunStoreObject get(String key) {
			return this.objects.get(key);
		}

		@Override
		public URI presignGet(String key, int expiresInSeconds, String contentType, String filename) {
			assertThat(contentType).isEqualTo("application/octet-stream");
			assertThat(filename).isEqualTo("loss.png");
			return URI.create("https://download.invalid/exact?expires=" + expiresInSeconds);
		}

	}

}
