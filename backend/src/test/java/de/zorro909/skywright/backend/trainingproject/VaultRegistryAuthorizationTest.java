package de.zorro909.skywright.backend.trainingproject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.sun.net.httpserver.HttpServer;
import de.zorro909.skywright.backend.credential.CredentialBinding;
import de.zorro909.skywright.backend.credential.VaultBindings;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VaultRegistryAuthorizationTest {

	@TempDir
	Path directory;

	@Test
	void exactResolverIsExchangedForPullTokenWithoutImagePullIdentityOrAnonymousFallback() throws Exception {
		var resolverId = UUID.randomUUID();
		var pullId = UUID.randomUUID();
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		var calls = new AtomicInteger();
		var status = new AtomicInteger(200);
		server.createContext("/v1/skywright/data/resolver", exchange -> {
			assertThat(exchange.getRequestURI().getQuery()).isEqualTo("version=2");
			var body = "{\"data\":{\"metadata\":{\"version\":2},\"data\":{\"username\":\"resolver\",\"token\":\"private-token\"}}}"
				.getBytes();
			exchange.sendResponseHeaders(status.get(), body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.createContext("/token", exchange -> {
			calls.incrementAndGet();
			assertThat(exchange.getRequestURI().getQuery())
				.isEqualTo("service=ghcr.io&scope=repository:owner/project:pull");
			assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
				.isEqualTo("Basic cmVzb2x2ZXI6cHJpdmF0ZS10b2tlbg==");
			var body = "{\"token\":\"scoped-bearer\"}".getBytes();
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		try {
			var endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
			var token = directory.resolve("token");
			Files.writeString(token, "vault-test-token");
			var binding = new CredentialBinding(resolverId, 2, "resolver", CredentialBinding.Kind.GHCR,
					"ghcr.io/owner/project", "backend-resolver", "resolver", "owner/project", "pull",
					Instant.now().minusSeconds(60), null, true);
			var vault = new VaultBindings(endpoint, "skywright", token, List.of(binding), Clock.systemUTC());
			var projects = new TrainingProjectRepository(null) {
				@Override
				List<TrainingProjectEntity> findAll() {
					return List.of(TrainingProjectEntity.create(UUID.randomUUID(), "project",
							new RegistryBinding(1, "ghcr.io/owner/project", RegistryAccessMode.PRIVATE, resolverId,
									pullId, RegistryReadiness.READY, "active")));
				}
			};
			var authorization = new VaultRegistryAuthorization(projects, vault, endpoint.resolve("/token"));
			assertThat(authorization.authorization("ghcr.io/owner/project")).contains("Bearer scoped-bearer");
			assertThat(calls).hasValue(1);
			status.set(503);
			assertThatThrownBy(() -> authorization.authorization("ghcr.io/owner/project"))
				.isInstanceOf(TrainingProjectException.class)
				.hasMessageNotContaining("private-token")
				.hasMessageNotContaining("vault-test-token");
			assertThat(calls).hasValue(1);
			assertThat(authorization.authorization("ghcr.io/owner/other")).isEmpty();
		}
		finally {
			server.stop(0);
		}
	}

}
