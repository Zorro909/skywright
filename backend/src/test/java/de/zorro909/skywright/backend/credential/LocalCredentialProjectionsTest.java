package de.zorro909.skywright.backend.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalCredentialProjectionsTest {

	@TempDir
	Path directory;

	private static final Instant NOW = Instant.parse("2026-09-05T12:00:00Z");

	@Test
	void cloudImagePullUsesItsRecordedRegistryRevisionThroughTheSecretChannel() throws Exception {
		var registry = new CredentialBinding(UUID.randomUUID(), 1, "registry/pull", CredentialBinding.Kind.GHCR,
				"ghcr.io/example/project", "execution-target-pull", "pull-reader", "example/project", "read-only", NOW,
				null, true);
		var dataset = binding("training-process", "dataset", 1);
		var output = binding("training-process", "run-store", 1);
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			String data = exchange.getRequestURI().getPath().contains("registry")
					? "{\"username\":\"pull-reader\",\"token\":\"pull-sentinel\"}"
					: "{\"accessKeyId\":\"storage-reader\",\"secretAccessKey\":\"storage-sentinel\"}";
			byte[] body = ("{\"data\":{\"metadata\":{\"version\":1},\"data\":" + data + "}}").getBytes();
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		try {
			var token = directory.resolve("cloud-token");
			Files.writeString(token, "vault-sentinel");
			var endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
			var facts = new MemoryFacts();
			var service = new LocalCredentialProjections(new VaultBindings(endpoint, "skywright", token,
					List.of(dataset, output, registry), Clock.fixed(NOW, ZoneOffset.UTC)), facts);
			var runId = UUID.randomUUID();
			try (var projected = service.cloudTraining(runId, selection(dataset), selection(output),
					selection(registry), NOW.plusSeconds(60))) {
				assertThat(projected.<String>send(values -> values.get("SKYPILOT_DOCKER_PASSWORD")))
					.isEqualTo("pull-sentinel");
				assertThat(projected.<String>send(values -> values.get("SKYPILOT_DOCKER_SERVER"))).isEqualTo("ghcr.io");
				assertThat(projected.toString()).doesNotContain("pull-sentinel", "storage-sentinel");
			}
			var rotated = new LocalCredentialProjections(new VaultBindings(endpoint, "skywright", token,
					List.of(revision(dataset, 2), revision(output, 2), revision(registry, 2)),
					Clock.fixed(NOW, ZoneOffset.UTC)), facts);
			try (var restored = rotated.restoreCloudTraining(runId)) {
				assertThat(restored.<String>send(values -> values.get("SKYPILOT_DOCKER_PASSWORD")))
					.isEqualTo("pull-sentinel");
			}
			try (var local = rotated.restoreTraining(runId)) {
				assertThat(local.<Set<String>>send(values -> values.keySet()))
					.noneMatch(key -> key.startsWith("SKYPILOT_DOCKER_"));
			}
			assertThat(facts.forConsumer(runId)).extracting(LocalProjectionFacts.Fact::slot)
				.containsExactlyInAnyOrder("dataset", "run-store", "runtime-pull");
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void isolatesRolesPinsRevisionsAndRetainsProjectionAcrossVaultLoss() throws Exception {
		var calls = new AtomicInteger();
		var status = new AtomicInteger(200);
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			calls.incrementAndGet();
			var revision = exchange.getRequestURI().getQuery().substring("version=".length());
			var body = ("{\"data\":{\"metadata\":{\"version\":" + revision + "},\"data\":{\"accessKeyId\":\"key-"
					+ revision + "\",\"secretAccessKey\":\"sentinel\"}}}")
				.getBytes();
			exchange.sendResponseHeaders(status.get(), body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		try {
			var dataset = binding("training-process", "dataset", 1);
			var store = binding("training-process", "run-store", 1);
			var backend = binding("backend", "run-store", 1);
			var token = directory.resolve("token");
			Files.writeString(token, "vault-sentinel");
			var endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
			var facts = new MemoryFacts();
			var service = new LocalCredentialProjections(new VaultBindings(endpoint, "skywright", token,
					List.of(dataset, store, backend), Clock.fixed(NOW, ZoneOffset.UTC)), facts);
			assertThatThrownBy(() -> service.training(UUID.randomUUID(), selection(dataset), selection(backend),
					NOW.plusSeconds(60)))
				.hasMessage("Training Credential Binding is unavailable");
			var runId = UUID.randomUUID();
			try (var projection = service.training(runId, selection(dataset), selection(store), NOW.plusSeconds(60))) {
				assertThat(projection.<Set<String>>send(values -> values.keySet())).containsExactlyInAnyOrder(
						"SKYWRIGHT_DATASET_ACCESS_KEY_ID", "SKYWRIGHT_DATASET_SECRET_ACCESS_KEY",
						"SKYWRIGHT_RUN_STORE_ACCESS_KEY_ID", "SKYWRIGHT_RUN_STORE_SECRET_ACCESS_KEY");
				assertThatThrownBy(
						() -> service.training(runId, selection(dataset), selection(store), NOW.plusSeconds(60)))
					.hasMessage("Already projected");
				status.set(404);
				int beforeRecovery = calls.get();
				assertThat(projection.<String>send(values -> values.get("SKYWRIGHT_RUN_STORE_ACCESS_KEY_ID")))
					.isEqualTo("key-1");
				assertThat(calls.get()).isEqualTo(beforeRecovery);
				assertThatThrownBy(() -> service.training(UUID.randomUUID(), selection(dataset), selection(store),
						NOW.plusSeconds(60)))
					.hasMessage("Training Credential Binding is MISSING");
				assertThat(projection.toString()).doesNotContain("sentinel", "vault-sentinel");
				assertThatThrownBy(() -> projection.send(values -> {
					throw new IllegalArgumentException("sentinel");
				})).hasMessage("Credential Projection transport failed").hasNoCause();
			}
			status.set(200);
			var nextDataset = revision(dataset, 2);
			var nextStore = revision(store, 2);
			var next = new LocalCredentialProjections(new VaultBindings(endpoint, "skywright", token,
					List.of(nextDataset, nextStore), Clock.fixed(NOW, ZoneOffset.UTC)), facts);
			try (var restored = next.restoreTraining(runId)) {
				assertThat(restored.<String>send(values -> values.get("SKYWRIGHT_DATASET_ACCESS_KEY_ID")))
					.isEqualTo("key-1");
				assertThat(restored.<String>send(values -> values.get("SKYWRIGHT_RUN_STORE_ACCESS_KEY_ID")))
					.isEqualTo("key-1");
			}
			status.set(404);
			assertThatThrownBy(() -> next.restoreTraining(runId)).hasMessage("Training Credential Binding is MISSING");
			status.set(200);
			var projection = next.training(UUID.randomUUID(), selection(nextDataset), selection(nextStore),
					NOW.plusSeconds(60));
			assertThat(projection.<String>send(values -> values.get("SKYWRIGHT_DATASET_ACCESS_KEY_ID")))
				.isEqualTo("key-2");
			projection.close();
			assertThatThrownBy(() -> projection.send(values -> true))
				.hasMessage("Credential Projection has been released");
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void taskTextCannotCarryKnownCredentialEnvironmentSlots() {
		for (var name : List.of("SKYWRIGHT_RUN_STORE_SECRET_ACCESS_KEY", "VAULT_TOKEN", "AWS_ACCESS_KEY_ID",
				"KUBECONFIG", "VAST_API_KEY", "SKYPILOT_DOCKER_PASSWORD")) {
			assertThatThrownBy(() -> new de.zorro909.skywright.backend.orchestration.OrchestratorTaskSpecification(
					"fixture", null, "train",
					List.of(new de.zorro909.skywright.backend.orchestration.OrchestratorTaskSpecification.Resources(
							"kubernetes", "2", "4", null, null, false)),
					java.util.Map.of(name, "sentinel")))
				.hasMessage("Credentials must not enter the task environment")
				.hasNoCause();
		}
	}

	@Test
	void runtimePullFileIsReadOnlyAndRemovedOnClose() throws Exception {
		Path file;
		try (var projection = new RuntimePullProjection(directory, "pull-reader", "pull-sentinel")) {
			file = projection.file();
			assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("r--------"));
			assertThat(Files.getPosixFilePermissions(file.getParent()))
				.isEqualTo(PosixFilePermissions.fromString("rwx------"));
			assertThat(Files.readString(file)).contains("ghcr.io", "auths");
			assertThat(projection.toString()).doesNotContain("pull-sentinel");
		}
		assertThat(Files.exists(file)).isFalse();
		assertThat(Files.exists(file.getParent())).isFalse();
	}

	private static CredentialBinding binding(String role, String resource, long revision) {
		UUID id = UUID.randomUUID();
		return new CredentialBinding(id, revision, "local/" + id, CredentialBinding.Kind.S3, resource, role,
				id.toString(), "project", resource.equals("dataset") ? "read-only" : "read-write-delete",
				NOW.minusSeconds(60), null, true);
	}

	private static CredentialBinding revision(CredentialBinding binding, long revision) {
		return new CredentialBinding(binding.id(), revision, binding.path(), binding.kind(), binding.resource(),
				binding.role(), binding.identity(), binding.scope(), binding.accessProfile(), binding.validatedAt(),
				null, true);
	}

	private static LocalCredentialProjections.Selection selection(CredentialBinding binding) {
		return new LocalCredentialProjections.Selection(binding.id(), binding.resource(), binding.accessProfile());
	}

	private static final class MemoryFacts extends LocalProjectionFacts {

		private final Set<String> slots = new HashSet<>();

		private final java.util.Map<String, CredentialBinding> metadata = new java.util.HashMap<>();

		MemoryFacts() {
			super(null, Clock.systemUTC());
		}

		@Override
		public void begin(UUID consumerId, String slot, CredentialBinding binding) {
			if (!this.slots.add(consumerId + "/" + slot)) {
				throw new IllegalStateException("Already projected");
			}
			metadata.put(consumerId + "/" + slot, binding);
		}

		@Override
		public java.util.Optional<CredentialBinding> recordedBinding(UUID consumer, String slot) {
			return java.util.Optional.ofNullable(metadata.get(consumer + "/" + slot));
		}

		@Override
		public List<Fact> forConsumer(UUID consumer) {
			return metadata.entrySet()
				.stream()
				.filter(e -> e.getKey().startsWith(consumer + "/"))
				.map(e -> new Fact(consumer, e.getKey().substring(37), e.getValue().id(), e.getValue().revision(),
						e.getValue().role(), NOW, null))
				.toList();
		}

	}

}
