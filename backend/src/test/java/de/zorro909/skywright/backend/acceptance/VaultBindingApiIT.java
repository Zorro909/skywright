package de.zorro909.skywright.backend.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import com.sun.net.httpserver.HttpServer;
import de.zorro909.skywright.backend.credential.CredentialBinding;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

@Tag("real-service")
class VaultBindingApiIT {

	@TempDir
	Path directory;

	@Test
	void packagedBackendReadsConfiguredVaultBindingsAndKeepsOutageCapabilitySpecific() throws Exception {
		var vault = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		var status = new AtomicInteger(200);
		vault.createContext("/", exchange -> {
			byte[] body = "{\"data\":{\"metadata\":{\"version\":2},\"data\":{\"username\":\"fixture\",\"token\":\"secret-sentinel\"}}}"
				.getBytes();
			exchange.sendResponseHeaders(status.get(), body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		vault.start();
		try (var database = PostgreSqlFixture.freshDatabase()) {
			var resolver = UUID.randomUUID();
			var pull = UUID.randomUUID();
			var manifest = directory.resolve("bindings.json");
			var json = JsonMapper.builder().build();
			var bindings = List.of(binding(resolver, "backend-resolver"), binding(pull, "execution-target-pull"));
			Files.writeString(manifest, json.writeValueAsString(bindings));
			var token = directory.resolve("token");
			Files.writeString(token, "vault-sentinel");
			var args = new ArrayList<>(database.backendArguments());
			int port = BackendProcess.availablePort();
			args.addAll(List.of("--server.port=" + port, "--skywright.deployment.environment=test",
					"--skywright.deployment.reporting-currency=EUR",
					"--skywright.credentials.vault.address=http://127.0.0.1:" + vault.getAddress().getPort(),
					"--skywright.credentials.vault.token-file=" + token,
					"--skywright.credentials.vault.bindings-file=" + manifest));
			try (var backend = BackendProcess.start(args.toArray(String[]::new))) {
				try {
					BackendProcess.awaitReadiness(port, Duration.ofSeconds(30));
				}
				catch (AssertionError error) {
					throw new AssertionError(backend.output(), error);
				}
				String body = "{\"displayName\":\"Private\",\"registry\":{\"repository\":\"ghcr.io/example/private\",\"accessMode\":\"private\",\"resolverCredentialBindingId\":\""
						+ resolver + "\",\"executionCredentialBindingId\":\"" + pull + "\"}}";
				var created = request(port, "POST", "/api/v1/training-projects", body);
				assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
				assertThat(created.body()).contains("\"readiness\":\"ready\"")
					.doesNotContain("secret-sentinel", "vault-sentinel");
				String id = json.readTree(created.body()).path("id").asText();
				status.set(503);
				var unavailable = request(port, "GET", "/api/v1/training-projects/" + id, "");
				assertThat(unavailable.statusCode()).isEqualTo(200);
				assertThat(unavailable.body()).contains("\"readiness\":\"unavailable\"")
					.doesNotContain("secret-sentinel", "vault-sentinel");
				assertThat(request(port, "GET", "/actuator/health/readiness", "").statusCode()).isEqualTo(200);
				status.set(200);
				assertThat(request(port, "GET", "/api/v1/training-projects/" + id, "").body())
					.contains("\"readiness\":\"ready\"");
				assertThat(backend.output()).doesNotContain("secret-sentinel", "vault-sentinel");
			}
		}
		finally {
			vault.stop(0);
		}
	}

	private CredentialBinding binding(UUID id, String role) {
		return new CredentialBinding(id, 2, "local/" + id, CredentialBinding.Kind.GHCR, "ghcr.io/example/private", role,
				id.toString(), "example/private", "pull", Instant.now().minusSeconds(60), null, true);
	}

	private HttpResponse<String> request(int port, String method, String path, String body) throws Exception {
		return HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
				.timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/json")
				.method(method, HttpRequest.BodyPublishers.ofString(body))
				.build(), HttpResponse.BodyHandlers.ofString());
	}

}
