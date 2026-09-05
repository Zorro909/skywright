package de.zorro909.skywright.backend.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class LocalProjectionBoundaryTest {

	private static final Instant NOW = Instant.parse("2026-09-05T12:00:00Z");

	@TempDir
	Path directory;

	@Test
	void refusesSharedExternalIdentityBeforeResolvingEitherStorageSlot() {
		var dataset = binding(CredentialBinding.Kind.S3, "training-process", "dataset", "shared-user", "read-only",
				null);
		var store = binding(CredentialBinding.Kind.S3, "training-process", "outputs", "shared-user",
				"read-write-delete", null);
		var vault = new VaultBindings(URI.create("http://127.0.0.1:1"), "skywright", directory.resolve("absent"),
				List.of(dataset, store), Clock.fixed(NOW, ZoneOffset.UTC));
		var facts = new RecordingFacts();
		assertThatThrownBy(() -> new LocalCredentialProjections(vault, facts).training(UUID.randomUUID(),
				selection(dataset), selection(store), NOW.plusSeconds(60)))
			.hasMessage("Dataset and Run Store require distinct external identities");
		assertThat(facts.begun).isEmpty();
	}

	@Test
	void requiresReadOnlyPullAndValidityStrictlyPastTheRecoveryBoundary() throws Exception {
		var pull = binding(CredentialBinding.Kind.GHCR, "execution-target-pull", "ghcr.io/project/image", "pull-user",
				"read-only", NOW.plusSeconds(60));
		var broad = binding(CredentialBinding.Kind.GHCR, "execution-target-pull", "ghcr.io/project/image", "broad-user",
				"read-write", null);
		var dataset = binding(CredentialBinding.Kind.S3, "training-process", "dataset", "reader", "read-only",
				NOW.plusSeconds(60));
		var store = binding(CredentialBinding.Kind.S3, "training-process", "outputs", "writer", "read-write-delete",
				null);
		try (var fixture = new VaultFixture(List.of(pull, broad, dataset, store),
				"{\"username\":\"fixture\",\"token\":\"pull-sentinel\"}")) {
			var facts = new RecordingFacts();
			var broker = new LocalCredentialProjections(fixture.vault, facts);
			assertThatThrownBy(
					() -> broker.runtimePull(UUID.randomUUID(), selection(broad), NOW.plusSeconds(10), directory))
				.hasMessage("Runtime pull binding is unavailable");
			assertThatThrownBy(
					() -> broker.runtimePull(UUID.randomUUID(), selection(pull), NOW.plusSeconds(60), directory))
				.hasMessage("Credential validity does not cover the Run recovery window");
			assertThatThrownBy(
					() -> broker.training(UUID.randomUUID(), selection(dataset), selection(store), NOW.plusSeconds(60)))
				.hasMessage("Credential validity does not cover the Run recovery window");
			assertThat(fixture.calls).hasValue(0);
			assertThat(facts.begun).isEmpty();
			try (var projected = broker.runtimePull(UUID.randomUUID(), selection(pull), NOW.plusSeconds(59),
					directory)) {
				assertThat(Files.readString(projected.file())).contains("ghcr.io");
			}
			assertThat(fixture.calls).hasValue(1);
		}
	}

	@Test
	void backendCallsRecordAndReleaseTheirOwnProjectionOnSuccessAndFailure() throws Exception {
		var binding = binding(CredentialBinding.Kind.SKYPILOT, "backend", "https://sky.invalid", "backend-client",
				"control", null);
		try (var fixture = new VaultFixture(List.of(binding), "{\"token\":\"backend-sentinel\"}")) {
			var facts = new RecordingFacts();
			var auth = new BackendSkyPilotAuthorization(fixture.vault, binding.id(), binding.resource(), facts);
			assertThat(auth.<String>use(token -> {
				assertThat(facts.begun).hasSize(1);
				assertThat(facts.released).isEmpty();
				return token;
			})).isEqualTo("backend-sentinel");
			assertThat(facts.released).containsExactly(facts.begun.getFirst().consumerId());
			assertThatThrownBy(() -> auth.use(token -> {
				throw new IllegalStateException(token);
			})).hasMessage("Backend SkyPilot authorization failed").hasNoCause();
			assertThat(facts.begun).hasSize(2).allSatisfy(fact -> {
				assertThat(fact.bindingId()).isEqualTo(binding.id());
				assertThat(fact.role()).isEqualTo("backend");
				assertThat(fact.revision()).isEqualTo(1);
			});
			assertThat(facts.released)
				.containsExactlyElementsOf(facts.begun.stream().map(LocalProjectionFacts.Fact::consumerId).toList());
			assertThat(JsonMapper.builder().build().writeValueAsString(facts.begun)).doesNotContain("backend-sentinel",
					"vault-sentinel");
		}
	}

	private static CredentialBinding binding(CredentialBinding.Kind kind, String role, String resource, String identity,
			String profile, Instant expiry) {
		UUID id = UUID.randomUUID();
		return new CredentialBinding(id, 1, "local/" + id, kind, resource, role, identity, "project", profile,
				NOW.minusSeconds(60), expiry, expiry == null);
	}

	private static LocalCredentialProjections.Selection selection(CredentialBinding binding) {
		return new LocalCredentialProjections.Selection(binding.id(), binding.resource(), binding.accessProfile());
	}

	private final class VaultFixture implements AutoCloseable {

		private final HttpServer server;

		private final VaultBindings vault;

		private final AtomicInteger calls = new AtomicInteger();

		VaultFixture(List<CredentialBinding> bindings, String secret) throws Exception {
			this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			this.server.createContext("/", exchange -> {
				this.calls.incrementAndGet();
				var bytes = ("{\"data\":{\"metadata\":{\"version\":1},\"data\":" + secret + "}}").getBytes();
				exchange.sendResponseHeaders(200, bytes.length);
				exchange.getResponseBody().write(bytes);
				exchange.close();
			});
			this.server.start();
			var token = directory.resolve("token");
			Files.writeString(token, "vault-sentinel");
			this.vault = new VaultBindings(URI.create("http://127.0.0.1:" + this.server.getAddress().getPort()),
					"skywright", token, bindings, Clock.fixed(NOW, ZoneOffset.UTC));
		}

		@Override
		public void close() {
			this.server.stop(0);
		}

	}

	private static class RecordingFacts extends LocalProjectionFacts {

		private final List<Fact> begun = new ArrayList<>();

		private final List<UUID> released = new ArrayList<>();

		RecordingFacts() {
			super(null, Clock.fixed(NOW, ZoneOffset.UTC));
		}

		@Override
		public void begin(UUID consumerId, String slot, CredentialBinding binding) {
			this.begun.add(new Fact(consumerId, slot, binding.id(), binding.revision(), binding.role(), NOW, null));
		}

		@Override
		public void release(UUID consumerId) {
			this.released.add(consumerId);
		}

	}

}
