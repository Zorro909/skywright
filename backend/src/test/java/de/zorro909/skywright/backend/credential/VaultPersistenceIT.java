package de.zorro909.skywright.backend.credential;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("real-service")
class VaultPersistenceIT {

	@TempDir
	Path directory;

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final HttpClient client = HttpClient.newHttpClient();

	@Test
	void initializedPersistentVaultFailsClosedWhileSealedAndRecoversExactRevisionFromBackup() throws Exception {
		try (var container = new GenericContainer<>("hashicorp/vault:1.21.4").withEnv("SKIP_SETCAP", "true")
			.withEnv("VAULT_LOCAL_CONFIG", """
					{"disable_mlock":true,"storage":{"file":{"path":"/vault/file"}},
					 "listener":{"tcp":{"address":"0.0.0.0:8200","tls_disable":true}},
					 "api_addr":"http://127.0.0.1:8200"}
					""")
			.withCommand("server")
			.withExposedPorts(8200)
			.waitingFor(Wait.forHttp("/v1/sys/seal-status").forStatusCode(200))) {
			container.start();
			var endpoint = URI.create("http://" + container.getHost() + ":" + container.getMappedPort(8200));
			var init = request(endpoint, "PUT", "/v1/sys/init", "", "{\"secret_shares\":1,\"secret_threshold\":1}");
			String root = init.path("root_token").asText();
			String key = init.path("keys_base64").get(0).asText();
			unseal(endpoint, key);
			request(endpoint, "POST", "/v1/sys/mounts/skywright", root,
					"{\"type\":\"kv\",\"options\":{\"version\":\"2\"}}");
			var binding = VaultBindingsTest.binding(UUID.randomUUID(), CredentialBinding.Kind.S3, "backend", null);
			String path = "/v1/skywright/data/" + binding.path();
			request(endpoint, "POST", path, root,
					"{\"data\":{\"accessKeyId\":\"old\",\"secretAccessKey\":\"old-secret\"}}");
			request(endpoint, "POST", path, root,
					"{\"data\":{\"accessKeyId\":\"current\",\"secretAccessKey\":\"current-secret\"}}");
			request(endpoint, "POST", "/v1/sys/policies/acl/backend", root, JSON.writeValueAsString(java.util.Map
				.of("policy", "path \"skywright/data/" + binding.path() + "\" { capabilities = [\"read\"] }")));
			var issued = request(endpoint, "POST", "/v1/auth/token/create", root,
					"{\"policies\":[\"backend\"],\"no_default_policy\":true,\"ttl\":\"1h\"}");
			var tokenFile = directory.resolve("vault-token");
			Files.writeString(tokenFile, issued.path("auth").path("client_token").asText());
			var vault = new VaultBindings(endpoint, "skywright", tokenFile, List.of(binding), Clock.systemUTC());
			assertReady(vault, binding);
			request(endpoint, "PUT", "/v1/sys/seal", root, "{}");
			assertThat(vault.readiness(binding.id(), 2, "backend").status())
				.isEqualTo(VaultBindings.Status.UNAVAILABLE);
			assertThat(container.execInContainer("sh", "-c", "cp -a /vault/file /tmp/vault-backup").getExitCode())
				.isZero();
			container.getDockerClient().restartContainerCmd(container.getContainerId()).exec();
			awaitSealed(endpoint);
			assertThat(vault.readiness(binding.id(), 2, "backend").status())
				.isEqualTo(VaultBindings.Status.UNAVAILABLE);
			unseal(endpoint, key);
			assertReady(vault, binding);
			request(endpoint, "PUT", "/v1/skywright/destroy/" + binding.path(), root, "{\"versions\":[2]}");
			assertThat(vault.readiness(binding.id(), 2, "backend").status()).isNotEqualTo(VaultBindings.Status.READY);
			request(endpoint, "PUT", "/v1/sys/seal", root, "{}");
			assertThat(container
				.execInContainer("sh", "-c", "rm -rf /vault/file/* && cp -a /tmp/vault-backup/. /vault/file/")
				.getExitCode()).isZero();
			container.getDockerClient().restartContainerCmd(container.getContainerId()).exec();
			awaitSealed(endpoint);
			unseal(endpoint, key);
			assertReady(new VaultBindings(endpoint, "skywright", tokenFile, List.of(binding), Clock.systemUTC()),
					binding);
		}
	}

	private void assertReady(VaultBindings vault, CredentialBinding binding) {
		assertThat(vault.readiness(binding.id(), 2, "backend").status()).isEqualTo(VaultBindings.Status.READY);
		assertThat(vault.resolve(binding.id(), 2, "backend", secret -> secret.path("accessKeyId").asText()).value())
			.contains("current");
	}

	private void unseal(URI endpoint, String key) throws Exception {
		request(endpoint, "PUT", "/v1/sys/unseal", "", JSON.writeValueAsString(java.util.Map.of("key", key)));
	}

	private void awaitSealed(URI endpoint) throws Exception {
		long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
		while (System.nanoTime() < deadline) {
			try {
				if (request(endpoint, "GET", "/v1/sys/seal-status", "", "").path("sealed").asBoolean()) {
					return;
				}
			}
			catch (java.io.IOException ignored) {
			}
			Thread.sleep(100);
		}
		throw new AssertionError("Vault did not restart sealed");
	}

	private JsonNode request(URI endpoint, String method, String path, String token, String body) throws Exception {
		var response = client.send(HttpRequest.newBuilder(endpoint.resolve(path))
			.timeout(Duration.ofSeconds(5))
			.header("X-Vault-Token", token)
			.method(method, HttpRequest.BodyPublishers.ofString(body))
			.build(), HttpResponse.BodyHandlers.ofString());
		// Never include a Vault response body in assertion diagnostics.
		assertThat(response.statusCode()).isBetween(200, 299);
		return response.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(response.body());
	}

}
