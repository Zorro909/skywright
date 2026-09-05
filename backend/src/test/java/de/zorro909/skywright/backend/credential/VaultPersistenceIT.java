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
			assertDirectAgentProjection(container, endpoint, root);
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
			endpoint = awaitSealed(container);
			vault = new VaultBindings(endpoint, "skywright", tokenFile, List.of(binding), Clock.systemUTC());
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
			endpoint = awaitSealed(container);
			vault = new VaultBindings(endpoint, "skywright", tokenFile, List.of(binding), Clock.systemUTC());
			unseal(endpoint, key);
			assertReady(vault, binding);
		}
	}

	private void assertDirectAgentProjection(GenericContainer<?> container, URI endpoint, String root)
			throws Exception {
		String path = "/v1/skywright/data/local/skypilot-kubernetes";
		for (String revision : List.of("old", "pinned-kubernetes", "newer")) {
			request(endpoint, "POST", path, root,
					JSON.writeValueAsString(java.util.Map.of("data", java.util.Map.of("kubeconfig", revision))));
		}
		request(endpoint, "POST", "/v1/sys/policies/acl/skypilot-projector", root, JSON.writeValueAsString(java.util.Map
			.of("policy", "path \"skywright/data/local/skypilot-kubernetes\" { capabilities = [\"read\"] }")));
		var issued = request(endpoint, "POST", "/v1/auth/token/create", root,
				"{\"policies\":[\"skypilot-projector\"],\"ttl\":\"1h\"}");
		container.copyFileToContainer(org.testcontainers.images.builder.Transferable
			.of(issued.path("auth").path("client_token").asText(), 0400), "/run/skywright/skypilot-vault-token");
		var rootDirectory = Path.of(System.getProperty("repository.root"));
		var fixtures = rootDirectory.resolve("deployment/examples/local-credentials");
		container.copyFileToContainer(org.testcontainers.images.builder.Transferable
			.of(Files.readString(fixtures.resolve("kubeconfig.ctmpl"))), "/etc/skywright/kubeconfig.ctmpl");
		container.copyFileToContainer(org.testcontainers.images.builder.Transferable
			.of(Files.readString(fixtures.resolve("vault-agent.hcl"))), "/etc/skywright/agent.hcl");
		var result = container.execInContainer("timeout", "30", "vault", "agent", "-config=/etc/skywright/agent.hcl");
		assertThat(result.getExitCode()).as((result.getStdout() + result.getStderr())
			.replace(issued.path("auth").path("client_token").asText(), "[redacted]")).isZero();
		assertThat(container.execInContainer("cat", "/run/skywright/kubeconfig").getStdout().strip())
			.isEqualTo("pinned-kubernetes");
		assertThat(container.execInContainer("stat", "-c", "%a", "/run/skywright/kubeconfig").getStdout().strip())
			.isEqualTo("400");
		assertThat(result.getStdout() + result.getStderr()).doesNotContain("pinned-kubernetes",
				issued.path("auth").path("client_token").asText());
		container.execInContainer("rm", "/run/skywright/kubeconfig", "/run/skywright/skypilot-vault-token");
	}

	private void assertReady(VaultBindings vault, CredentialBinding binding) {
		assertThat(vault.readiness(binding.id(), 2, "backend").status()).isEqualTo(VaultBindings.Status.READY);
		assertThat(vault.resolve(binding.id(), 2, "backend", secret -> secret.path("accessKeyId").asText()).value())
			.contains("current");
	}

	private void unseal(URI endpoint, String key) throws Exception {
		request(endpoint, "PUT", "/v1/sys/unseal", "", JSON.writeValueAsString(java.util.Map.of("key", key)));
	}

	private URI awaitSealed(GenericContainer<?> container) throws Exception {
		long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
		while (System.nanoTime() < deadline) {
			try {
				// Docker may allocate a different random host port on restart.
				// GenericContainer caches its initial inspection.
				var mapping = container.getCurrentContainerInfo()
					.getNetworkSettings()
					.getPorts()
					.getBindings()
					.get(com.github.dockerjava.api.model.ExposedPort.tcp(8200));
				if (mapping == null || mapping.length == 0 || mapping[0] == null) {
					Thread.sleep(100);
					continue;
				}
				URI endpoint = URI.create("http://" + container.getHost() + ":" + mapping[0].getHostPortSpec());
				if (request(endpoint, "GET", "/v1/sys/seal-status", "", "").path("sealed").asBoolean()) {
					return endpoint;
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
