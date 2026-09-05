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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class VaultBindingsTest {

	@TempDir
	Path directory;

	private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");

	private static final String SECRET = "never-in-diagnostics";

	static CredentialBinding binding(UUID id, CredentialBinding.Kind kind, String role, Instant expiry) {
		return new CredentialBinding(id, 2, "local/" + id, kind, "resource", role, id.toString(), "project-prefix",
				"read-only", NOW.minusSeconds(60), expiry, expiry == null);
	}

	@Test
	void exactVersionRoleExpiryAndCapabilityIsolation() throws Exception {
		var calls = new AtomicInteger();
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			calls.incrementAndGet();
			assertThat(exchange.getRequestURI().getQuery()).isEqualTo("version=2");
			assertThat(exchange.getRequestHeaders().getFirst("X-Vault-Token")).isEqualTo("test-token");
			byte[] response = ("{\"data\":{\"metadata\":{\"version\":2},\"data\":{\"accessKeyId\":\"key\",\"secretAccessKey\":\""
					+ SECRET + "\"}}}")
				.getBytes();
			exchange.sendResponseHeaders(200, response.length);
			exchange.getResponseBody().write(response);
			exchange.close();
		});
		server.start();
		try {
			var ready = binding(UUID.randomUUID(), CredentialBinding.Kind.S3, "backend", null);
			var expired = binding(UUID.randomUUID(), CredentialBinding.Kind.S3, "training-process", NOW);
			var transfer = binding(UUID.randomUUID(), CredentialBinding.Kind.S3, "transfer-worker", null);
			var vault = vault(server, List.of(ready, expired, transfer));
			assertThat(vault.readiness(ready.id(), 1, "backend").status()).isEqualTo(VaultBindings.Status.INVALID);
			assertThat(vault.readiness(ready.id(), 2, "training-process").status())
				.isEqualTo(VaultBindings.Status.INVALID);
			assertThat(vault.readiness(expired.id(), 2, "training-process").status())
				.isEqualTo(VaultBindings.Status.EXPIRED);
			assertThat(vault.readiness(UUID.randomUUID(), 2, "backend").status())
				.isEqualTo(VaultBindings.Status.MISSING);
			assertThat(calls).hasValue(0);
			assertThat(vault.readiness(ready.id(), 2, "backend").status()).isEqualTo(VaultBindings.Status.READY);
			assertThat(new VaultRoleAccess(vault).credentials(ready.id(), 2, "backend")
				.orElseThrow()
				.resolveCredentials()
				.secretAccessKey()).isEqualTo(SECRET);
			var roles = new VaultRoleAccess(vault);
			assertThat(roles.credentials(transfer.id(), 2, "transfer-worker")).isPresent();
			assertThat(roles.credentials(transfer.id(), 2, "backend")).isEmpty();
			assertThat(roles.credentials(ready.id(), 2, "transfer-worker")).isEmpty();
			assertThat(roles.credentials(transfer.id(), 1, "transfer-worker")).isEmpty();

			var resolution = vault.resolve(ready.id(), 2, "backend", value -> {
				throw new IllegalStateException(SECRET);
			});
			assertThat(resolution.status()).isEqualTo(VaultBindings.Status.INVALID);
			assertThat(resolution.toString()).doesNotContain(SECRET);
			assertThat(JsonMapper.builder().build().writeValueAsString(vault.readiness(ready.id(), 2, "backend")))
				.doesNotContain(SECRET, "test-token", "secretAccessKey");
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void missingDeniedSealedMalformedAndWrongVersionsFailClosed() throws Exception {
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		var status = new AtomicInteger(404);
		server.createContext("/", exchange -> {
			byte[] response = ("{\"errors\":[\"" + SECRET + "\"],\"data\":{\"metadata\":{\"version\":99}}}").getBytes();
			exchange.sendResponseHeaders(status.get(), response.length);
			exchange.getResponseBody().write(response);
			exchange.close();
		});
		server.start();
		var binding = binding(UUID.randomUUID(), CredentialBinding.Kind.GHCR, "backend-resolver", null);
		var vault = vault(server, List.of(binding));
		try {
			for (var code : List.of(404, 403, 503, 200, 302)) {
				status.set(code);
				var result = vault.readiness(binding.id(), 2, binding.role());
				assertThat(result.status()).isEqualTo(switch (code) {
					case 404 -> VaultBindings.Status.MISSING;
					case 403, 200 -> VaultBindings.Status.INVALID;
					default -> VaultBindings.Status.UNAVAILABLE;
				});
				assertThat(result.toString()).doesNotContain(SECRET);
			}
		}
		finally {
			server.stop(0);
		}
		assertThat(vault.readiness(binding.id(), 2, binding.role()).status())
			.isEqualTo(VaultBindings.Status.UNAVAILABLE);
	}

	@Test
	void rejectsSharedIdentityAndPathTraversal() throws Exception {
		var binding = binding(UUID.randomUUID(), CredentialBinding.Kind.S3, "backend", null);
		assertThatThrownBy(() -> new VaultBindings(URI.create("http://127.0.0.1:8200"), "skywright",
				directory.resolve("token"), List.of(binding, binding), Clock.systemUTC()))
			.hasMessageContaining("distinct");
		assertThatThrownBy(() -> new CredentialBinding(UUID.randomUUID(), 1, "../other", CredentialBinding.Kind.S3,
				"resource", "backend", "identity", "scope", "read", NOW, null, true))
			.hasMessage("Invalid Credential Binding metadata");
	}

	@Test
	void validatesEveryLocalRoleAndRejectsMalformedOrBroaderSecretShapes() throws Exception {
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		var payload = new java.util.concurrent.atomic.AtomicReference<String>();
		server.createContext("/", exchange -> {
			var bytes = ("{\"data\":{\"metadata\":{\"version\":2},\"data\":" + payload.get() + "}}").getBytes();
			exchange.sendResponseHeaders(200, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		server.start();
		try {
			var json = JsonMapper.builder().build();
			for (var role : List.of("backend", "training-process", "metric-view", "backend-resolver",
					"execution-target-pull", "skypilot-api-server", "backend-service")) {
				var kind = switch (role) {
					case "backend", "training-process", "metric-view" -> CredentialBinding.Kind.S3;
					case "backend-resolver", "execution-target-pull" -> CredentialBinding.Kind.GHCR;
					case "skypilot-api-server" -> CredentialBinding.Kind.KUBERNETES;
					default -> CredentialBinding.Kind.SKYPILOT;
				};
				var binding = binding(UUID.randomUUID(), kind, role.equals("backend-service") ? "backend" : role, null);
				var secret = json.createObjectNode();
				switch (kind) {
					case S3 -> secret.put("accessKeyId", "key").put("secretAccessKey", SECRET);
					case GHCR -> secret.put("username", "user").put("token", SECRET);
					case SKYPILOT -> secret.put("token", SECRET);
					case KUBERNETES -> secret.put("kubeconfig",
							"""
									{"apiVersion":"v1","kind":"Config","current-context":"local",
									 "clusters":[{"name":"local","cluster":{"server":"resource","certificate-authority-data":"Y2E="}}],
									 "users":[{"name":"local","user":{"token":"test-token"}}],
									 "contexts":[{"name":"local","context":{"cluster":"local","user":"local"}}]}
									""");
				}
				payload.set(json.writeValueAsString(secret));
				var vault = vault(server, List.of(binding));
				assertThat(vault.readiness(binding.id(), 2, binding.role()).status()).as(role)
					.isEqualTo(VaultBindings.Status.READY);
				if (kind == CredentialBinding.Kind.S3) {
					assertThat(new VaultRoleAccess(vault).readiness(binding.id(), 2, binding.role()))
						.isEqualTo(de.zorro909.skywright.backend.targetstorage.BindingReadiness.READY);
				}
				secret.put("unexpectedCredential", SECRET);
				payload.set(json.writeValueAsString(secret));
				assertThat(vault.readiness(binding.id(), 2, binding.role()).status()).as(role)
					.isEqualTo(VaultBindings.Status.INVALID);
				payload.set("{}");
				assertThat(vault.readiness(binding.id(), 2, binding.role()).status()).as(role)
					.isEqualTo(VaultBindings.Status.INVALID);
			}
		}
		finally {
			server.stop(0);
		}
	}

	private VaultBindings vault(HttpServer server, List<CredentialBinding> bindings) throws Exception {
		var token = directory.resolve("token");
		Files.writeString(token, "test-token\n");
		return new VaultBindings(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "skywright", token,
				bindings, Clock.fixed(NOW, ZoneOffset.UTC));
	}

}
